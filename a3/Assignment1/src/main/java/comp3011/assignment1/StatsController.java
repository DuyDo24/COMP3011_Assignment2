package comp3011.assignment1;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/global")
public class StatsController {

    private final TokenStats tokenStats;

    @Autowired
    public StatsController(TokenStats tokenStats) {
        this.tokenStats = tokenStats;
    }

    @GetMapping("/stats")
    public Map<String, Object> getStats() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("inputTokens", tokenStats.getInputTokens());
        body.put("outputTokens", tokenStats.getOutputTokens());
        return body;
    }
}