package org.cloudfoundry.credhub.controller.v1;

import org.cloudfoundry.credhub.audit.CEFAuditRecord;
import org.cloudfoundry.credhub.audit.EventAuditLogService;
import org.cloudfoundry.credhub.audit.entity.InterpolateCredentials;
import org.cloudfoundry.credhub.handler.InterpolationHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping(path = InterpolationController.API_V1, produces = MediaType.APPLICATION_JSON_UTF8_VALUE)
public class InterpolationController {

  static final String API_V1 = "/api/v1";
  private final InterpolationHandler jsonInterpolationHandler;
  private final EventAuditLogService eventAuditLogService;
  private CEFAuditRecord auditRecord;

  @Autowired
  InterpolationController(
      InterpolationHandler jsonInterpolationHandler,
      EventAuditLogService eventAuditLogService,
      CEFAuditRecord auditRecord) {
    this.jsonInterpolationHandler = jsonInterpolationHandler;
    this.eventAuditLogService = eventAuditLogService;
    this.auditRecord = auditRecord;
  }

  @RequestMapping(method = RequestMethod.POST, path = "/interpolate")
  @ResponseStatus(HttpStatus.OK)
  public Map<String, Object> interpolate(
      @RequestBody Map<String, Object> requestBody
  ) {
    auditRecord.setRequestDetails(new InterpolateCredentials());
    return eventAuditLogService.auditEvents((eventAuditRecordParametersList ->
          jsonInterpolationHandler
            .interpolateCredHubReferences(requestBody, eventAuditRecordParametersList))
    );
  }
}
