// "Remove erroneous '!= null'" "true"

import java.util.Objects;

public class Test {
  void test(String foo) {
    Objects.requireNonNull(foo <caret>!= null);
  }

  native int foo();
}