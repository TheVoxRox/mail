package org.voxrox.mailbackend.feature.mail.controller;

import java.util.List;

import jakarta.validation.constraints.Positive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.voxrox.mailbackend.feature.mail.dto.FolderResponse;
import org.voxrox.mailbackend.feature.mail.service.MailFacade;
import org.voxrox.mailbackend.util.LogCategory;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * REST API for listing folders of an account. Returns the display name, the
 * opaque folderRef and the role
 * (INBOX/SENT/TRASH/DRAFTS/JUNK/ARCHIVE/NEWSLETTERS/USER), which lets the
 * client work with folders independently of the provider.
 */
@Tag(name = "Folders", description = "Account folder listing with folderRef and detected role.")
@RestController
@RequestMapping("/api/v1/accounts/{accountId}/folders")
@Validated
public class MailFolderController {

    private static final Logger log = LoggerFactory.getLogger(MailFolderController.class);

    private final MailFacade mailFacade;

    public MailFolderController(MailFacade mailFacade) {
        this.mailFacade = mailFacade;
    }

    @Operation(summary = "List account folders", description = "Returns folders with display name, folderRef and detected role (INBOX/SENT/TRASH/DRAFTS/JUNK/ARCHIVE/NEWSLETTERS/USER).")
    @GetMapping
    public List<FolderResponse> listFolders(@PathVariable @Positive(message = "{validation.positive}") Long accountId) {
        log.debug("{} Listing folders for account {}", LogCategory.API, accountId);
        return mailFacade.getFolders(accountId);
    }
}
