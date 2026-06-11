package org.voxrox.mailbackend.core.clientconfig;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Client Config", description = "Safe runtime limits and capability values for the desktop client.")
@RestController
@RequestMapping("/api/v1/client-config")
public class ClientConfigController {

    private final ClientConfigService clientConfigService;

    public ClientConfigController(ClientConfigService clientConfigService) {
        this.clientConfigService = clientConfigService;
    }

    @Operation(summary = "Client runtime configuration", description = "Returns only safe limits and recommendations that the desktop client can use for UX validation. The backend remains the source of truth.")
    @GetMapping
    public ClientConfigResponse getClientConfig() {
        return clientConfigService.getClientConfig();
    }
}
