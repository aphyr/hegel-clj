package hegel_clj;

import clojure.lang.IExceptionInfo;
import clojure.lang.IPersistentMap;

public class HegelError extends RuntimeException implements IExceptionInfo {
  public final IPersistentMap data;

  public HegelError(final String message, final IPersistentMap data) {
    super(message);
    this.data = data;
  }

  public HegelError(final String message, final IPersistentMap data, Throwable cause) {
    super(message, cause);
    this.data = data;
  }

  public IPersistentMap getData() {
    return data;
  }
}
