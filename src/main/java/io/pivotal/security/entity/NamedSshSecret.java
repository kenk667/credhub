package io.pivotal.security.entity;

import io.pivotal.security.view.SecretKind;
import org.apache.commons.codec.binary.Base64;

import javax.persistence.DiscriminatorValue;
import javax.persistence.Entity;
import javax.persistence.Table;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.math.BigInteger;
import java.util.Arrays;

@Entity
@Table(name = "SshSecret")
@DiscriminatorValue("ssh")
public class NamedSshSecret extends NamedRsaSshSecret {
  private final static String SSH_PREFIX = "ssh-rsa ";
  public static final int SMALL_BUFFER_SIZE = 10;

  public NamedSshSecret() {
    this(null);
  }

  public NamedSshSecret(String name) {
    super(name);
  }

  public SecretKind getKind() {
    return SecretKind.SSH;
  }

  // https://www.ietf.org/rfc/rfc4253.txt - section 6.6
  // we are parsing the key which consists of multiple precision integers to derive modulus and hence key length
  public int getKeyLength() {
    return parsePublicKey().keyLength;
  }

  public String getComment() {
    return parsePublicKey().comment;
  }

  private ParsedPublicKeyValues parsePublicKey() {
    String publicKey = getPublicKey();
    ParsedPublicKeyValues values = new ParsedPublicKeyValues();

    try {
      String[] publicKeyParts = publicKey.split(" ");
      String publicKeyWithoutPrefix = publicKeyParts[1];
      values.comment = publicKeyParts.length > 2 ? publicKeyParts[2] : "";

      DataInputStream dataStream = new DataInputStream(new ByteArrayInputStream(Base64.decodeBase64(publicKeyWithoutPrefix)));
      int length = dataStream.readInt();
      byte[] buf = new byte[SMALL_BUFFER_SIZE];
      // read and ignore type - it's always just "ssh-rsa"
      dataStream.read(buf, 0, length);
      // read and ignore exponent
      length = dataStream.readInt();
      dataStream.read(buf, 0, length);
      // read modulus
      length = dataStream.readInt();
      buf = new byte[length];
      dataStream.read(buf, 0, length);
      BigInteger modulus = new BigInteger(Arrays.copyOf(buf, length));
      // calculate key length
      values.keyLength = modulus.bitLength();

      return values;

    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static class ParsedPublicKeyValues {
    public int keyLength;
    public String comment;
  }
}
