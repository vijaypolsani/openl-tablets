package org.openl.rules.tbasic;

import java.io.File;

import org.junit.Test;
import org.openl.rules.TestUtils;

public class TestVar extends Test0 {
    @Test
    public void test1() {
        Exception ex = catchEx(new File("rules/tbasic1/VAR_F1.xls"));
        TestUtils.assertEx(ex, "Operation must have Condition value");
    }

    @Test
    public void test2() {
        Exception ex = catchEx(new File("rules/tbasic1/VAR_F2.xls"));
        TestUtils.assertEx(ex, "Operation can not be multiline, i.e. can not have nested operations");
    }

    @Test
    public void test3() {
        okRows(new File("rules/tbasic1/VAR_P1.xls"), 0);
    }
}
