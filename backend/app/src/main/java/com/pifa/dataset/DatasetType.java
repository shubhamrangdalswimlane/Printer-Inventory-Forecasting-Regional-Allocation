package com.pifa.dataset;

import java.util.List;

/**
 * Kinds of dataset version (PRD 8.5). Only the two upload types exist in Phase 1; the snapshot
 * types are created by the workflow phases and are listed here so the column values are fixed now.
 */
public enum DatasetType {
    /** A forecast Excel file received via SFTP, UI or the generator. Lands on the Forecaster dashboard. */
    FORECAST_UPLOAD("Forecast", "FC", true),
    /** An inventory Excel file. Lands on the Planner dashboard. */
    INVENTORY_UPLOAD("Inventory", "INV", true),
    /** Saved copy of Forecaster edits before a new upload replaces the working copy (Phase 2). */
    FORECAST_WORKING_SNAPSHOT("", "FCW", false),
    /** Frozen forecast at Forecaster approval (Phase 2). */
    FORECAST_PUBLISHED("", "FCP", false),
    /** Frozen forecast after Regional Manager revisions (Phase 2). */
    FORECAST_REVISED("", "FCR", false),
    /** Planner-approved allocation (Phase 3). */
    ALLOCATION_APPROVED("", "ALA", false),
    /** Locked final allocation (Phase 4). */
    ALLOCATION_FINAL("", "ALF", false);

    private final String sheetName;
    private final String labelPrefix;
    private final boolean upload;

    DatasetType(String sheetName, String labelPrefix, boolean upload) {
        this.sheetName = sheetName;
        this.labelPrefix = labelPrefix;
        this.upload = upload;
    }

    /** @return the worksheet name the Excel template uses ({@code Forecast} or {@code Inventory}) */
    public String sheetName() { return sheetName; }

    /** @return short prefix used in version labels, e.g. {@code FC} for {@code FC-2026-11-v3} */
    public String labelPrefix() { return labelPrefix; }

    /** @return true for types created by file ingestion (as opposed to workflow snapshots) */
    public boolean isUpload() { return upload; }

    /** @return true if lines of this type live in {@code forecast_line} */
    public boolean isForecastShaped() {
        return this == FORECAST_UPLOAD || this == FORECAST_WORKING_SNAPSHOT
            || this == FORECAST_PUBLISHED || this == FORECAST_REVISED;
    }

    /** @return the header names every file of this upload type must contain (regions are added by the caller) */
    public List<String> fixedRequiredHeaders() {
        return switch (this) {
            case FORECAST_UPLOAD -> List.of("Product_name", "Forecast_Month");
            case INVENTORY_UPLOAD -> List.of("Product_name", "Available_Qty", "As_Of_Date");
            default -> List.of();
        };
    }

    /**
     * Maps the URL segment used by the API to an upload type.
     *
     * @param segment {@code forecast} or {@code inventory} (any case)
     * @return the matching upload type
     * @throws IllegalArgumentException for any other value
     */
    public static DatasetType fromPath(String segment) {
        return switch (segment.toLowerCase()) {
            case "forecast" -> FORECAST_UPLOAD;
            case "inventory" -> INVENTORY_UPLOAD;
            default -> throw new IllegalArgumentException("Unknown dataset type '" + segment + "' (use forecast or inventory)");
        };
    }
}
