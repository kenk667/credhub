package io.pivotal.security.service;

import static com.greghaskins.spectrum.Spectrum.beforeEach;
import static com.greghaskins.spectrum.Spectrum.describe;
import static com.greghaskins.spectrum.Spectrum.it;
import static com.jayway.jsonpath.matchers.JsonPathMatchers.hasJsonPath;
import static io.pivotal.security.entity.AuditingOperationCode.CREDENTIAL_ACCESS;
import static io.pivotal.security.helper.JsonHelper.serializeToString;
import static io.pivotal.security.helper.SpectrumHelper.wireAndUnwire;
import static io.pivotal.security.util.AuthConstants.EXPIRED_KEY_JWT;
import static io.pivotal.security.util.CurrentTimeProvider.makeCalendar;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.isA;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.greghaskins.spectrum.Spectrum;
import io.pivotal.security.data.OperationAuditRecordDataService;
import io.pivotal.security.entity.NamedValueSecretData;
import io.pivotal.security.entity.OperationAuditRecord;
import io.pivotal.security.fake.FakeSecretRepository;
import io.pivotal.security.fake.FakeTransactionManager;
import io.pivotal.security.util.CurrentTimeProvider;
import io.pivotal.security.util.DatabaseProfileResolver;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.provider.OAuth2Authentication;
import org.springframework.security.oauth2.provider.authentication.OAuth2AuthenticationDetails;
import org.springframework.security.oauth2.provider.token.ResourceServerTokenServices;
import org.springframework.security.oauth2.provider.token.TokenStore;
import org.springframework.test.context.ActiveProfiles;

@RunWith(Spectrum.class)
@ActiveProfiles(value = {"unit-test"}, resolver = DatabaseProfileResolver.class)
@SpringBootTest
public class AuditLogServiceTest {

  AuditLogService subject;

  @MockBean
  OperationAuditRecordDataService operationAuditRecordDataService;

  FakeSecretRepository secretRepository;

  FakeTransactionManager transactionManager;

  @MockBean
  CurrentTimeProvider currentTimeProvider;

  @Autowired
  TokenStore tokenStore;

  @Autowired
  ResourceServerTokenServices tokenServices;

  @MockBean
  SecurityEventsLogService securityEventsLogService;

  @Autowired
  MessageSource messageSource;

  private final Instant now = Instant.ofEpochSecond(1490903353);

  private ResponseEntity<?> responseEntity;
  private OAuth2Authentication authentication;

  {
    wireAndUnwire(this);

    beforeEach(() -> {
      authentication = tokenStore.readAuthentication(EXPIRED_KEY_JWT);
      OAuth2AuthenticationDetails mockDetails = mock(OAuth2AuthenticationDetails.class);
      when(mockDetails.getTokenValue()).thenReturn(EXPIRED_KEY_JWT);
      authentication.setDetails(mockDetails);

      transactionManager = new FakeTransactionManager();
      secretRepository = new FakeSecretRepository(transactionManager);

      when(currentTimeProvider.getInstant()).thenReturn(now);
      when(currentTimeProvider.getNow()).thenReturn(makeCalendar(now.toEpochMilli()));

      subject = new AuditLogService(
          currentTimeProvider,
          tokenServices,
          operationAuditRecordDataService,
          transactionManager,
          messageSource,
          securityEventsLogService
      );
      subject.init();
    });

    describe("logging behavior", () -> {
      describe("when the action succeeds", () -> {
        describe("when the audit succeeds", () -> {
          beforeEach(() -> {
            responseEntity = subject.performWithAuditing(auditRecordBuilder -> {
              return auditedSaveAndReturnNewValue(auditRecordBuilder);
            });
          });

          it("performs the action", () -> {
            assertThat(secretRepository.count(), equalTo(1L));
          });

          it("passes the request untouched", () -> {
            assertThat(responseEntity.getStatusCode(), equalTo(HttpStatus.OK));
          });

          it("logs audit entry", () -> {
            checkAuditRecord(true, HttpStatus.OK);
          });

          it("logs in CEF format to file", () -> {
            verify(securityEventsLogService).log(isA(OperationAuditRecord.class));
          });
        });

        describe("when the database audit fails", () -> {
          beforeEach(() -> {
            doThrow(new RuntimeException()).when(operationAuditRecordDataService)
                .save(any(OperationAuditRecord.class));

            responseEntity = subject.performWithAuditing(auditRecordBuilder -> {
              return auditedSaveAndReturnNewValue(auditRecordBuilder);
            });
          });

          it("does not perform the action", () -> {
            assertThat(secretRepository.count(), equalTo(0L));
          });

          it("returns 500", () -> {
            assertThat(responseEntity.getStatusCode(), equalTo(HttpStatus.INTERNAL_SERVER_ERROR));
            assertThat(serializeToString(responseEntity.getBody()), hasJsonPath("$.error", equalTo(
                "The request could not be completed. Please contact your system administrator to"
                    + " resolve this issue.")));
          });

          it("should not write to the CEF log", () -> {
            verify(securityEventsLogService, times(0)).log(isA(OperationAuditRecord.class));
          });
        });
      });

      describe("when the action fails with an exception", () -> {
        describe("when the audit succeeds", () -> {
          AtomicReference<Exception> exception = new AtomicReference<>();
          RuntimeException re = new RuntimeException("controller method failed");

          beforeEach(() -> {
            exception.set(null);
            try {
              subject.performWithAuditing(auditRecordBuilder -> {
                auditRecordBuilder.setCredentialName("keyName");
                auditRecordBuilder.populateFromRequest(
                    new MockHttpServletRequest("GET", "requestURI"));
                auditRecordBuilder.setAuthentication(authentication);

                NamedValueSecretData entity = new NamedValueSecretData("keyName");
                entity.setEncryptedValue("value".getBytes());
                secretRepository.save(entity);
                throw re;
              });
            } catch (Exception e) {
              exception.set(e);
            }
          });

          it("leaves the 500 response from the controller alone", () -> {
            assertThat(exception.get(), equalTo(re));
          });

          it("logs failed audit entry", () -> {
            checkAuditRecord(false, HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(secretRepository.count(), equalTo(0L));
          });

          it("should write to the CEF log file", () -> {
            verify(securityEventsLogService).log(isA(OperationAuditRecord.class));
          });
        });

        describe("when the database audit fails", () -> {
          beforeEach(() -> {
            doThrow(new RuntimeException()).when(operationAuditRecordDataService)
                .save(any(OperationAuditRecord.class));

            responseEntity = subject.performWithAuditing(auditRecordBuilder -> {
              auditRecordBuilder.setCredentialName("keyName");
              auditRecordBuilder.populateFromRequest(
                  new MockHttpServletRequest("GET", "requestURI"));
              auditRecordBuilder.setAuthentication(authentication);

              NamedValueSecretData entity = new NamedValueSecretData("keyName");
              entity.setEncryptedValue("value".getBytes());
              secretRepository.save(entity);
              throw new RuntimeException("controller method failed");
            });
          });

          it("rolls back both original and audit repository transactions", () -> {
            assertThat(secretRepository.count(), equalTo(0L));
          });

          it("returns 500 and original error message", () -> {
            assertThat(responseEntity.getStatusCode(), equalTo(HttpStatus.INTERNAL_SERVER_ERROR));
            assertThat(serializeToString(responseEntity.getBody()), hasJsonPath("$.error", equalTo(
                "The request could not be completed. Please contact your system administrator "
                    + "to resolve this issue.")));
          });

          it("should not write to the CEF log", () -> {
            verify(securityEventsLogService, times(0)).log(isA(OperationAuditRecord.class));
          });
        });
      });

      describe("when the action fails with a non 200 status", () -> {
        describe("when the audit succeeds", () -> {
          beforeEach(() -> {
            responseEntity = subject.performWithAuditing(auditRecordBuilder -> {
              return auditedSaveNewValueWithBadGateway(auditRecordBuilder);
            });
          });

          it("logs audit entry for failure", () -> {
            checkAuditRecord(false, HttpStatus.BAD_GATEWAY);
            assertThat(secretRepository.count(), equalTo(0L));
          });

          it("returns the non-2xx status code", () -> {
            assertThat(responseEntity.getStatusCode(), equalTo(HttpStatus.BAD_GATEWAY));
          });

          it("should write to the CEF log file", () -> {
            verify(securityEventsLogService).log(isA(OperationAuditRecord.class));
          });
        });

        describe("when the database audit fails", () -> {
          beforeEach(() -> {
            doThrow(new RuntimeException()).when(operationAuditRecordDataService)
                .save(any(OperationAuditRecord.class));

            responseEntity = subject.performWithAuditing(auditRecordBuilder -> {
              return auditedSaveNewValueWithBadGateway(auditRecordBuilder);
            });
          });

          it("rolls back both original and audit repository transactions", () -> {
            assertThat(transactionManager.hasOpenTransaction(), is(false));
            assertThat(secretRepository.count(), equalTo(0L));
          });

          it("returns 500", () -> {
            assertThat(responseEntity.getStatusCode(), equalTo(HttpStatus.INTERNAL_SERVER_ERROR));
            assertThat(serializeToString(responseEntity.getBody()), hasJsonPath("$.error", equalTo(
                "The request could not be completed. Please contact your system administrator"
                    + " to resolve this issue.")));
          });

          it("should not write to the CEF log", () -> {
            verify(securityEventsLogService, times(0)).log(isA(OperationAuditRecord.class));
          });
        });

        describe("when audit transaction fails to commit", () -> {
          beforeEach(() -> {
            transactionManager.failOnCommit();
            responseEntity = subject.performWithAuditing(auditRecordBuilder -> {
              return auditedSaveNewValueWithBadGateway(auditRecordBuilder);
            });
          });

          it("doesn't rollback transaction", () -> {
            assertThat(transactionManager.hasOpenTransaction(), is(false));
            assertThat(secretRepository.count(), equalTo(0L));
          });

          it("returns 500", () -> {
            assertThat(responseEntity.getStatusCode(), equalTo(HttpStatus.INTERNAL_SERVER_ERROR));
            assertThat(serializeToString(responseEntity.getBody()), hasJsonPath("$.error", equalTo(
                "The request could not be completed. Please contact your system administrator"
                    + " to resolve this issue.")));
          });

          it("should not write to the CEF log", () -> {
            verify(securityEventsLogService, times(0)).log(isA(OperationAuditRecord.class));
          });
        });
      });
    });
  }

  private ResponseEntity<?> auditedSaveAndReturnNewValue(
      AuditRecordBuilder auditRecordBuilder) {
    auditRecordBuilder.setCredentialName("keyName");
    auditRecordBuilder.populateFromRequest(
        new MockHttpServletRequest("GET", "requestURI"));
    auditRecordBuilder.setAuthentication(authentication);
    NamedValueSecretData entity = new NamedValueSecretData("keyName");
    entity.setEncryptedValue("value".getBytes());
    final NamedValueSecretData secret = secretRepository.save(entity);
    return new ResponseEntity<>(secret, HttpStatus.OK);
  }

  private ResponseEntity<?> auditedSaveNewValueWithBadGateway(
      AuditRecordBuilder auditRecordBuilder) {
    auditRecordBuilder.setCredentialName("keyName");
    auditRecordBuilder.populateFromRequest(
        new MockHttpServletRequest("GET", "requestURI"));
    auditRecordBuilder.setAuthentication(authentication);

    NamedValueSecretData entity = new NamedValueSecretData("keyName");
    entity.setEncryptedValue("value".getBytes());
    secretRepository.save(entity);
    return new ResponseEntity<>(HttpStatus.BAD_GATEWAY);
  }

  private void checkAuditRecord(boolean successFlag, HttpStatus status) {
    ArgumentCaptor<OperationAuditRecord> recordCaptor = ArgumentCaptor
        .forClass(OperationAuditRecord.class);
    verify(operationAuditRecordDataService, times(1)).save(recordCaptor.capture());

    OperationAuditRecord actual = recordCaptor.getValue();
    assertThat(actual.getNow(), equalTo(now));
    assertThat(actual.getCredentialName(), equalTo("keyName"));
    assertThat(actual.getOperation(), equalTo(CREDENTIAL_ACCESS.toString()));
    assertThat(actual.getUserId(), equalTo("df0c1a26-2875-4bf5-baf9-716c6bb5ea6d"));
    assertThat(actual.getUserName(), equalTo("credhub_cli"));
    assertThat(actual.getUaaUrl(), equalTo("https://10.244.0.2:8443/oauth/token"));
    assertThat(actual.getAuthValidFrom(), equalTo(1090903353L));
    assertThat(actual.getAuthValidUntil(), equalTo(1290903354L));
    assertThat(actual.getPath(), equalTo("requestURI"));
    assertThat(actual.isSuccess(), equalTo(successFlag));
    assertThat(actual.getClientId(), equalTo("credhub_cli"));
    assertThat(actual.getScope(), equalTo("credhub.write,credhub.read"));
    assertThat(actual.getGrantType(), equalTo("password"));
    assertThat(actual.getMethod(), equalTo("GET"));
    assertThat(actual.getStatusCode(), equalTo(status.value()));
  }
}