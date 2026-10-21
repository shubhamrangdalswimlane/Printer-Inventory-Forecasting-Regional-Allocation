package com.pifa.forecast;

import com.pifa.common.ApiException;
import com.pifa.security.CurrentUser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * The editability matrix of PRD section 9.1 for forecast quantities, in one place.
 *
 * <p>An edit is allowed only if the caller's <em>role</em>, the <em>cycle state</em>, the
 * <em>region</em> and the region's <em>review status</em> all agree. The API calls this on every
 * write; the UI calls {@link #permissions} to know which cells to enable. Admin is read-only on
 * business quantities by design (separation of duties).
 *
 * <p>Failure codes: 403 when the caller may never do this (wrong role or someone else's region),
 * 409 when the caller could, but not now (wrong cycle state or region already submitted).
 */
public final class EditPolicy {

    private EditPolicy() {}

    /** The editable quantity columns. */
    public enum Field {
        /** Forecaster working value, editable in FORECAST_DRAFT. */
        PUBLISHED,
        /** Regional Manager value for the manager's own region, editable in REGIONAL_REVIEW while the region is OPEN. */
        REVISED,
        /** Planner's approved allocation, editable in PLANNING (deviation from the proposal needs a reason). */
        APPROVED
    }

    /**
     * Checks one edit.
     *
     * @param user         the caller
     * @param cycleState   state of the cycle, e.g. FORECAST_DRAFT
     * @param regionReview review status of the cell's region: OPEN or SUBMITTED (null if no status rows exist yet)
     * @param field        which column is being edited
     * @param region       region code of the cell
     * @throws ApiException 403 FORBIDDEN / REGION_FORBIDDEN, or 409 CYCLE_LOCKED / REGION_SUBMITTED
     */
    public static void require(CurrentUser user, String cycleState, String regionReview, Field field, String region) {
        switch (field) {
            case PUBLISHED -> {
                if (!user.has("forecaster")) throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only the Forecaster may edit the published forecast");
                if (!"FORECAST_DRAFT".equals(cycleState)) throw new ApiException(HttpStatus.CONFLICT, "CYCLE_LOCKED", "The forecast can only be edited while the cycle is FORECAST_DRAFT (now " + cycleState + ")");
            }
            case REVISED -> {
                if (!user.has("regional_manager")) throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only a Regional Manager may revise the forecast");
                if (!user.inRegion(region)) throw new ApiException(HttpStatus.FORBIDDEN, "REGION_FORBIDDEN", "You may only edit your own region(s); " + region + " is not one of them");
                if (!"REGIONAL_REVIEW".equals(cycleState)) throw new ApiException(HttpStatus.CONFLICT, "CYCLE_LOCKED", "Revisions can only be made while the cycle is REGIONAL_REVIEW (now " + cycleState + ")");
                if (!"OPEN".equals(regionReview)) throw new ApiException(HttpStatus.CONFLICT, "REGION_SUBMITTED", "Region " + region + " has already been submitted");
            }
            case APPROVED -> {
                if (!user.has("planner")) throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only the Planner may edit the approved allocation");
                if (!"PLANNING".equals(cycleState)) throw new ApiException(HttpStatus.CONFLICT, "CYCLE_LOCKED", "The allocation can only be edited while the cycle is PLANNING (now " + cycleState + ")");
            }
        }
    }

    /**
     * Describes what the caller may do right now, for the UI (GET /cycles/{id}/permissions).
     *
     * @param user          the caller
     * @param cycleState    state of the cycle
     * @param regionReviews review status per region code (OPEN/SUBMITTED); may be empty before publish
     * @return a map the front end turns into enabled/disabled controls
     */
    public static Map<String, Object> permissions(CurrentUser user, String cycleState, Map<String, String> regionReviews) {
        return permissions(user, cycleState, regionReviews, Map.of());
    }

    /**
     * Same as {@link #permissions(CurrentUser, String, Map)} plus the finalization-stage capabilities.
     *
     * @param regionFinalize finalize status per region code (OPEN/FINALIZED)
     */
    public static Map<String, Object> permissions(CurrentUser user, String cycleState, Map<String, String> regionReviews, Map<String, String> regionFinalize) {
        boolean forecaster = user.has("forecaster");
        boolean noneSubmitted = regionReviews.values().stream().noneMatch("SUBMITTED"::equals);
        List<String> editableRegions = new ArrayList<>();
        List<String> submittable = new ArrayList<>();
        if (user.has("regional_manager") && "REGIONAL_REVIEW".equals(cycleState)) {
            for (var e : regionReviews.entrySet()) {
                if (user.inRegion(e.getKey()) && "OPEN".equals(e.getValue())) {
                    editableRegions.add(e.getKey());
                    submittable.add(e.getKey());
                }
            }
        }
        boolean planning = user.has("planner") && "PLANNING".equals(cycleState);
        Map<String, Object> p = new java.util.LinkedHashMap<>();
        p.put("state", cycleState);
        p.put("canEditPublished", forecaster && "FORECAST_DRAFT".equals(cycleState));
        p.put("editableRegions", editableRegions);
        p.put("canPublish", forecaster && "FORECAST_DRAFT".equals(cycleState));
        p.put("canRecall", "REGIONAL_REVIEW".equals(cycleState) && ((forecaster && noneSubmitted) || user.has("admin")));
        p.put("submittableRegions", submittable);
        p.put("canProceed", user.has("planner") && "REGIONAL_REVIEW".equals(cycleState));
        p.put("canReturnRegions", planning);
        // Phase 3: Planner allocation
        p.put("canRunAllocation", planning);
        p.put("canEditAllocation", planning);
        p.put("canEditReserve", planning);
        p.put("canApproveAllocation", planning);
        // Phase 4: finalization and transfers
        boolean finalization = "REGIONAL_FINALIZATION".equals(cycleState);
        List<String> finalizable = new ArrayList<>();
        List<String> unfinalizable = new ArrayList<>();
        if (user.has("regional_manager") && finalization) {
            for (var e : regionFinalize.entrySet()) {
                if (!user.inRegion(e.getKey())) continue;
                if ("FINALIZED".equals(e.getValue())) unfinalizable.add(e.getKey()); else finalizable.add(e.getKey());
            }
        }
        p.put("finalizableRegions", finalizable);
        p.put("unfinalizableRegions", unfinalizable);
        p.put("canCreateTransfer", user.has("regional_manager") && finalization);
        p.put("canDecideAnyTransfer", user.has("planner") && finalization);
        p.put("canReopenAllocation", user.has("planner") && finalization);
        p.put("canReopenCycle", user.has("admin") && "FINAL".equals(cycleState));
        return p;
    }
}
