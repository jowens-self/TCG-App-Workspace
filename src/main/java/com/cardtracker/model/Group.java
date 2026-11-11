package com.cardtracker.model;

import java.time.LocalDate;

/** A set. {@code publishedOn} is its release date, or null when the catalog has none. */
public record Group(
        int groupId,
        int categoryId,
        String name,
        String abbreviation,
        boolean isSupplemental,
        LocalDate publishedOn,
        String modifiedOn) {
}
