package com.chk.paiements;
import static org.junit.Assert.*;
import org.junit.Test;

public final class SkillMajAutoTest {
    @Test public void comparisonsAreNumericNotLexicographic() {
        assertTrue(SkillMajAuto.newer("0.1.10","0.1.9"));
        assertTrue(SkillMajAuto.newer("1.0.0","0.99.99"));
        assertFalse(SkillMajAuto.newer("0.1.9","0.1.10"));
        assertFalse(SkillMajAuto.newer("0.1.0","0.1.0"));
        assertFalse(SkillMajAuto.newer("1.0.0","0.1.0-debug"));
        assertFalse(SkillMajAuto.newer("oops","0.1.0"));
    }
}
