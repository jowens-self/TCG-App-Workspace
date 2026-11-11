package com.cardtracker.source;

import com.cardtracker.model.Category;
import com.cardtracker.model.Group;
import com.cardtracker.model.PriceSnapshot;
import com.cardtracker.model.Product;

import java.time.LocalDate;
import java.util.List;

public interface PriceSource {

    String name();

    /**
     * The date the source's live data actually represents.
     *
     * Exists because a live price endpoint answers "what are the prices now",
     * not "which day is this", so a caller that stamps the response with its
     * own clock is recording when it asked, not what it got. Those differ
     * whenever the pull runs before the source's daily refresh, and the row
     * that results is a copy of the previous day wearing today's date.
     */
    LocalDate dataAsOf() throws Exception;

    List<Category> categories() throws Exception;

    List<Group> groups(int categoryId) throws Exception;

    List<Product> products(int categoryId, int groupId) throws Exception;

    List<PriceSnapshot> pricesForGroup(int categoryId, int groupId, LocalDate snapshotDate) throws Exception;

    int requestsUsed();
}
