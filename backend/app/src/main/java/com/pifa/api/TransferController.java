package com.pifa.api;

import com.pifa.allocation.TransferService;
import com.pifa.events.LiveEvents;
import com.pifa.security.CurrentUser;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Transfers between regions (PRD FR-RM-07, FR-RM-10, FR-PL-08) and the live-refresh stream. */
@RestController
@RequestMapping("/api/v1")
public class TransferController {

    /**
     * Body of a new transfer.
     *
     * @param productId  the product
     * @param fromRegion donor region
     * @param toRegion   receiving region
     * @param qty        units to move
     * @param reason     why
     */
    public record CreateRequest(UUID productId, String fromRegion, String toRegion, int qty, String reason, Boolean moveNow) {}

    /** @param comment optional comment on a decision */
    public record DecisionRequest(String comment) {}

    private final TransferService transfers;
    private final LiveEvents events;

    public TransferController(TransferService transfers, LiveEvents events) {
        this.transfers = transfers;
        this.events = events;
    }

    /** {@code GET /cycles/{id}/transfers?status=REQUESTED} - transfers of a cycle, newest first. */
    @GetMapping("/cycles/{id}/transfers")
    public List<TransferService.TransferRow> list(@PathVariable UUID id, @RequestParam(required = false) String status) {
        return transfers.list(id, status);
    }

    /** {@code POST /cycles/{id}/transfers} - request, offer, or (owning both regions, or with {@code moveNow=true}) move units at once. */
    @PostMapping("/cycles/{id}/transfers")
    public TransferService.Outcome create(@PathVariable UUID id, @RequestBody CreateRequest body, Authentication auth) {
        return transfers.create(id, body.productId(), body.fromRegion(), body.toRegion(), body.qty(), body.reason(), CurrentUser.from(auth), Boolean.TRUE.equals(body.moveNow()));
    }

    /** {@code POST /transfers/{id}/accept} - the deciding region (or the Planner) accepts; the units move. */
    @PostMapping("/transfers/{id}/accept")
    public TransferService.Outcome accept(@PathVariable UUID id, @RequestBody(required = false) DecisionRequest body, Authentication auth) {
        return transfers.accept(id, body == null ? null : body.comment(), CurrentUser.from(auth));
    }

    /** {@code POST /transfers/{id}/reject} */
    @PostMapping("/transfers/{id}/reject")
    public TransferService.Outcome reject(@PathVariable UUID id, @RequestBody(required = false) DecisionRequest body, Authentication auth) {
        return transfers.reject(id, body == null ? null : body.comment(), CurrentUser.from(auth));
    }

    /** {@code POST /transfers/{id}/cancel} - the creator (or the Planner) withdraws it. */
    @PostMapping("/transfers/{id}/cancel")
    public TransferService.Outcome cancel(@PathVariable UUID id, Authentication auth) {
        return transfers.cancel(id, CurrentUser.from(auth));
    }

    /**
     * {@code GET /events} - Server-Sent Events stream: {@code change} events with {@code {"cycleId","type"}}.
     * Requires the bearer token like every other endpoint (the client reads it with fetch, not EventSource).
     */
    @GetMapping(path = "/events", produces = "text/event-stream")
    public SseEmitter events() {
        return events.subscribe();
    }
}
