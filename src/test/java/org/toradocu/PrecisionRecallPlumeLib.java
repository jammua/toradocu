package org.toradocu;

import org.junit.Test;
import org.toradocu.testlib.AbstractPrecisionRecallTestSuite;

public class PrecisionRecallPlumeLib extends AbstractPrecisionRecallTestSuite {

  private static final String PLUMELIB_SRC = "src/test/resources/src/plume-lib-1.1.0/java/src";
  private static final String PLUMELIB_BIN = "src/test/resources/bin/plume-lib-1.1.0.jar";
  private static final String PLUMELIB_GOAL_DIR = "src/test/resources/goal-output/plume-lib-1.1.0/";

  public PrecisionRecallPlumeLib() {
    super(PLUMELIB_SRC, PLUMELIB_BIN, PLUMELIB_GOAL_DIR);
  }

  @Test
  public void testArraysMDE() throws Exception {
    test("plume.ArraysMDE", 0.889, 0.8);
  }

  @Test
  public void testRegexUtil() throws Exception {
    test("plume.RegexUtil", 1.0, 1.0);
  }

  @Test
  public void testFileCompiler() throws Exception {
    test("plume.FileCompiler", 1.0, 0.5);
  }

  @Test
  public void testWeakIdentityHashMap() throws Exception {
    test("plume.WeakIdentityHashMap", 0.75, 0.75);
  }
}
