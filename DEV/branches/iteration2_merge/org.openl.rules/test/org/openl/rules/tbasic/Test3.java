package org.openl.rules.tbasic;
import java.io.File;

import org.junit.Test;
import org.openl.rules.TestUtils;

public class Test3 extends Test0 {
    @Test
    public void test0() {
        Exception ex = catchEx(new File("rules/tbasic0/Algorithm3-0.xls"));
        TestUtils.assertEx(ex, "Unsufficient rows. Must be more than 2!");
    }

    @Test
    public void test1() {
        okRows(new File("rules/tbasic0/Algorithm3-1.xls"), 0);
    }

    @Test
    public void test2() {
        okRows(new File("rules/tbasic0/Algorithm3-2.xls"), 0);
    }

    @Test
    public void test3() {
        okRows(new File("rules/tbasic0/Algorithm3-3.xls"), 0);
    }

    @Test
    public void test4() {
        Exception ex = catchEx(new File("rules/tbasic0/Algorithm3-4.xls"));
        TestUtils.assertEx(ex, "org.openl.syntax.SyntaxErrorException:");
    }

    @Test
    public void test5() {
        Exception ex = catchEx(new File("rules/tbasic0/Algorithm3-4.xls"));
        TestUtils.assertEx(ex, "org.openl.syntax.SyntaxErrorException:");
    }

    @Test
    public void test6() {
        okRows(new File("rules/tbasic0/Algorithm3-6.xls"), 0);
    }

    @Test
    public void test7() {
        Exception ex = catchEx(new File("rules/tbasic0/Algorithm3-7.xls"));
        TestUtils.assertEx(ex, "Invalid column id");
    }

    @Test
    public void test8() {
        okRows(new File("rules/tbasic0/Algorithm3-8.xls"), 0);
    }

    @Test
    public void test9() {
        okRows(new File("rules/tbasic0/Algorithm3-9.xls"), 0);
    }
}
