package com.cardtracker.source.pokemontcg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the card catalog from pokemontcg.io, the only permitted image source
 * (see CLAUDE.md "Permitted hosts"; TCGplayer's CDN is off limits).
 *
 * Fetched PER SET, not by paging the whole card list. That's not arbitrary:
 * their deep pagination is broken. With pageSize=250, pages 50 and beyond
 * (offset ~12,250 of 20,479) return HTTP 500 every time, across repeated
 * attempts, so it is not a transient failure. Per-set queries are bounded and
 * work reliably.
 *
 * The API is also genuinely flaky even on good requests: an initial run saw
 * 11 of 174 sets fail with 500/502 and succeed on a later attempt. Treat
 * retries and resumability as required, not defensive.
 *
 * Etiquette, same as tcgcsv: descriptive User-Agent and paced requests. This
 * is a free community project.
 */
public class PokemonTcgCatalog {

    public static final String USER_AGENT = "CardTracker/1.0";
    private static final String BASE = "https://api.pokemontcg.io/v2";
    private static final int PAGE_SIZE = 250;
    private static final long PACING_MS = 400;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    /** One card, reduced to just what matching and downloading need. */
    public record Card(String id, String name, String number, String rarity,
                       String setId, String setName, Integer printedTotal,
                       String imageUrl) {
    }

    /** Every set id, newest first is irrelevant here; order doesn't matter. */
    public List<String> setIds() throws Exception {
        JsonNode root = getJson(BASE + "/sets?pageSize=" + PAGE_SIZE + "&select=id");
        List<String> ids = new ArrayList<>();
        for (JsonNode n : root.path("data")) {
            ids.add(n.path("id").asText());
        }
        return ids;
    }

    /**
     * All cards in one set. Paged, because a handful of sets exceed the page
     * size. An earlier version capped at one page and silently lost ~220
     * cards.
     */
    public List<Card> cardsInSet(String setId) throws Exception {
        List<Card> out = new ArrayList<>();
        int page = 1;
        while (true) {
            String url = BASE + "/cards?q=set.id:" + setId
                    + "&pageSize=" + PAGE_SIZE + "&page=" + page
                    + "&select=id,name,number,set,images,rarity";
            JsonNode root = getJson(url);
            JsonNode data = root.path("data");
            if (!data.isArray() || data.isEmpty()) break;
            for (JsonNode c : data) {
                JsonNode set = c.path("set");
                out.add(new Card(
                        c.path("id").asText(null),
                        c.path("name").asText(null),
                        c.path("number").asText(null),
                        c.path("rarity").asText(null),
                        set.path("id").asText(null),
                        set.path("name").asText(null),
                        set.hasNonNull("printedTotal") ? set.path("printedTotal").asInt() : null,
                        c.path("images").path("small").asText(null)));
            }
            if (out.size() >= root.path("totalCount").asInt(0)) break;
            page++;
            pace();
        }
        return out;
    }

    /** Raw image bytes. Separate host (images.pokemontcg.io), also permitted. */
    public byte[] download(String imageUrl) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(imageUrl))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(60))
                .GET().build();
        HttpResponse<byte[]> res = sendWithRetry(req, HttpResponse.BodyHandlers.ofByteArray());
        return res.body();
    }

    private JsonNode getJson(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(90))
                .GET().build();
        return mapper.readTree(sendWithRetry(req, HttpResponse.BodyHandlers.ofString()).body());
    }

    /**
     * Exponential backoff. Six attempts because the observed failures are
     * transient but persistent enough that three wasn't sufficient. A set
     * that failed four times in a row succeeded on a later run.
     */
    private <T> HttpResponse<T> sendWithRetry(HttpRequest req, HttpResponse.BodyHandler<T> handler)
            throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 6; attempt++) {
            try {
                HttpResponse<T> res = http.send(req, handler);
                if (res.statusCode() == 200) return res;
                last = new IOException("HTTP " + res.statusCode() + " for " + req.uri());
            } catch (Exception e) {
                last = e;
            }
            Thread.sleep((long) Math.pow(2, attempt) * 500L);
        }
        throw last;
    }

    public void pace() throws InterruptedException {
        Thread.sleep(PACING_MS);
    }
}
