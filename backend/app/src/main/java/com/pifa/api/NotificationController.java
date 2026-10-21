package com.pifa.api;

import com.pifa.notify.NotificationService;
import com.pifa.notify.NotificationService.NotificationRow;
import com.pifa.security.CurrentUser;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** In-app notification endpoints (PRD FR-COM-06). */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    /** {@code GET /notifications?limit=30} - the newest notifications visible to the caller, with an unread flag. */
    @GetMapping
    public List<NotificationRow> list(@RequestParam(defaultValue = "30") int limit, Authentication auth) {
        return notifications.forUser(CurrentUser.from(auth), limit);
    }

    /** {@code POST /notifications/{id}/read} - mark one notification as read for the caller. */
    @PostMapping("/{id}/read")
    public ResponseEntity<Void> read(@PathVariable long id, Authentication auth) {
        notifications.markRead(id, CurrentUser.from(auth));
        return ResponseEntity.noContent().build();
    }
}
