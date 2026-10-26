package org.openroadcode.androidbridge;

import static org.junit.Assert.*;
import org.junit.Test;

public final class SmsConnectionSetupTest {
  private final String id = "abcdefghijklmnopqrstuvwx";
  private final String token = "abcdefghijklmnopqrstuvwxyz1234567890";

  @Test public void approvalMustStayOnLocalSmsEndpoint() {
    String good = "http://127.0.0.1:8769/sms/connect/approve/" + id + "?token=" + token;
    assertTrue(SmsConnectionSetup.validApprovalUrl(id, good));
    assertFalse(SmsConnectionSetup.validApprovalUrl(id, good.replace("127.0.0.1", "example.org")));
    assertFalse(SmsConnectionSetup.validApprovalUrl(id, good.replace(":8769", ":8773")));
    assertFalse(SmsConnectionSetup.validApprovalUrl(id, good + "&redirect=http://example.org"));
    assertFalse(SmsConnectionSetup.validApprovalUrl(id, good.replace("/sms/connect/", "/pairing/browser/")));
  }

  @Test public void sessionReprDoesNotRevealApprovalOrPollSecrets() {
    SmsConnectionSetup.Session session = new SmsConnectionSetup.Session(id, token, "secret-url");
    assertFalse(session.toString().contains(token));
    assertFalse(session.toString().contains("secret-url"));
  }
}
