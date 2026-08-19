package sh.vork.channel;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/channels")
@PreAuthorize("isAuthenticated()")
public class ChannelController {

    private final ChannelService channelService;

    public ChannelController(ChannelService channelService) {
        this.channelService = channelService;
    }

    @GetMapping("/search")
    public List<ChannelRef> search(@RequestParam("q") String query,
                                   @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return channelService.search(query, Math.max(1, Math.min(limit, 100)));
    }

    @GetMapping("/{channelName}")
    public ResponseEntity<?> resolve(@PathVariable String channelName) {
        return channelService.resolveByChannelName(channelName)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of(
                        "status", "NOT_FOUND",
                        "message", "Channel not found: " + channelName)));
    }
}
