package com.pifa.forecast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pifa.common.ApiException;
import com.pifa.forecast.EditPolicy.Field;
import com.pifa.security.CurrentUser;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Tests every cell of the editability matrix (PRD 9.1) for forecast quantities: each role, each cycle
 * state, own region versus another region, region open versus submitted. Pure unit test, no Spring.
 */
class EditPolicyTest {

    private static final CurrentUser FORECASTER = new CurrentUser("f", Set.of("forecaster"));
    private static final CurrentUser PLANNER = new CurrentUser("p", Set.of("planner"));
    private static final CurrentUser ADMIN = new CurrentUser("a", Set.of("admin"));
    private static final CurrentUser RM_NORTH = new CurrentUser("n", Set.of("regional_manager"), Set.of("NORTH"));
    private static final List<String> STATES = List.of("FORECAST_DRAFT", "REGIONAL_REVIEW", "PLANNING", "REGIONAL_FINALIZATION", "FINAL");

    private static String codeOf(Runnable r) {
        try {
            r.run();
            return "OK";
        } catch (ApiException e) {
            return e.getStatus().value() + ":" + e.getCode();
        }
    }

    @Test
    void publishedColumnIsEditableOnlyByForecasterInDraft() {
        for (String state : STATES) {
            assertThat(codeOf(() -> EditPolicy.require(FORECASTER, state, null, Field.PUBLISHED, "NORTH")))
                .isEqualTo(state.equals("FORECAST_DRAFT") ? "OK" : "409:CYCLE_LOCKED");
            for (CurrentUser other : List.of(PLANNER, ADMIN, RM_NORTH)) {
                assertThat(codeOf(() -> EditPolicy.require(other, state, "OPEN", Field.PUBLISHED, "NORTH"))).isEqualTo("403:FORBIDDEN");
            }
        }
    }

    @Test
    void revisedColumnIsEditableOnlyByRegionOwnerInReviewWhileOpen() {
        for (String state : STATES) {
            for (String review : new String[]{"OPEN", "SUBMITTED"}) {
                String expected = !state.equals("REGIONAL_REVIEW") ? "409:CYCLE_LOCKED" : review.equals("OPEN") ? "OK" : "409:REGION_SUBMITTED";
                assertThat(codeOf(() -> EditPolicy.require(RM_NORTH, state, review, Field.REVISED, "NORTH"))).as(state + "/" + review).isEqualTo(expected);
                // another region is always forbidden, whatever the state
                assertThat(codeOf(() -> EditPolicy.require(RM_NORTH, state, review, Field.REVISED, "EAST"))).isEqualTo("403:REGION_FORBIDDEN");
            }
            for (CurrentUser other : List.of(FORECASTER, PLANNER, ADMIN)) {
                assertThat(codeOf(() -> EditPolicy.require(other, state, "OPEN", Field.REVISED, "NORTH"))).isEqualTo("403:FORBIDDEN");
            }
        }
    }

    @Test
    void regionMatchIsCaseInsensitive() {
        assertThatCode(() -> EditPolicy.require(RM_NORTH, "REGIONAL_REVIEW", "OPEN", Field.REVISED, "north")).doesNotThrowAnyException();
    }

    @Test
    void permissionsReflectRoleAndState() {
        Map<String, String> open = Map.of("NORTH", "OPEN", "EAST", "OPEN");
        assertThat(EditPolicy.permissions(FORECASTER, "FORECAST_DRAFT", Map.of()))
            .containsEntry("canEditPublished", true).containsEntry("canPublish", true);
        assertThat(EditPolicy.permissions(FORECASTER, "REGIONAL_REVIEW", open)).containsEntry("canRecall", true).containsEntry("canEditPublished", false);
        assertThat(EditPolicy.permissions(FORECASTER, "REGIONAL_REVIEW", Map.of("NORTH", "SUBMITTED"))).containsEntry("canRecall", false);
        assertThat(EditPolicy.permissions(ADMIN, "REGIONAL_REVIEW", Map.of("NORTH", "SUBMITTED"))).containsEntry("canRecall", true);
        assertThat(EditPolicy.permissions(RM_NORTH, "REGIONAL_REVIEW", open)).containsEntry("editableRegions", List.of("NORTH"));
        assertThat(EditPolicy.permissions(RM_NORTH, "REGIONAL_REVIEW", Map.of("NORTH", "SUBMITTED"))).containsEntry("editableRegions", List.of());
        assertThat(EditPolicy.permissions(PLANNER, "PLANNING", open)).containsEntry("canReturnRegions", true);
        assertThat(EditPolicy.permissions(PLANNER, "REGIONAL_REVIEW", open)).containsEntry("canProceed", true);
    }

    @Test
    void failureIsA409ForStateAndA403ForPermission() {
        assertThatThrownBy(() -> EditPolicy.require(RM_NORTH, "PLANNING", "OPEN", Field.REVISED, "NORTH"))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(409));
    }
}
