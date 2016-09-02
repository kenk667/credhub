package io.pivotal.security.view;

import java.util.Objects;
import java.util.function.Function;

public enum SecretKind implements SecretKindFromString {
  VALUE {
    @Override
    public <T, R> Function<T, R> map(Mapping<T, R> mapping) {
      return (t) -> mapping.value(this, t);
    }
  },
  PASSWORD {
    @Override
    public <T, R> Function<T, R> map(Mapping<T, R> mapping) {
      return (t) -> mapping.password(this, t);
    }
  },
  CERTIFICATE {
    @Override
    public <T, R> Function<T, R> map(Mapping<T, R> mapping) { return (t) -> mapping.certificate(this, t); }
  };

  public abstract <T, R> Function<T, R> map(Mapping<T, R> mapping);

  public interface Mapping<T, R> {
    R value(SecretKind secretKind, T t);
    R password(SecretKind secretKind, T t);
    R certificate(SecretKind secretKind, T t);

    default <V> Mapping<V, R> compose(Mapping<? super V, ? extends T> before) {
      Objects.requireNonNull(before);
      return new Mapping<V, R>() {
        @Override
        public R value(SecretKind secretKind, V v) {
          return Mapping.this.value(secretKind, before.value(secretKind, v));
        }

        @Override
        public R password(SecretKind secretKind, V v) {
          return Mapping.this.password(secretKind, before.password(secretKind, v));
        }

        @Override
        public R certificate(SecretKind secretKind, V v) {
          return Mapping.this.certificate(secretKind, before.certificate(secretKind, v));
        }
      };
    }
  }

  public static class StaticMapping<T, R> implements Mapping<T, R> {

    private final R value;
    private final R password;
    private final R certificate;

    public StaticMapping(R value, R password, R certificate) {
      this.value = value;
      this.password = password;
      this.certificate = certificate;
    }

    @Override
    public R value(SecretKind secretKind, T t) {
      return value;
    }

    @Override
    public R password(SecretKind secretKind, T t) {
      return password;
    }

    @Override
    public R certificate(SecretKind secretKind, T t) {
      return certificate;
    }
  }
}