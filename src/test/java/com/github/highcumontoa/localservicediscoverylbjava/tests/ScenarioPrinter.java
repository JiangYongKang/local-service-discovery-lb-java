package com.github.highcumontoa.localservicediscoverylbjava.tests;

import java.util.List;

/**
 * 场景验证打印工具：统一打印输入、候选实例状态与最终判定依据。
 */
public final class ScenarioPrinter {

    private ScenarioPrinter() {}

    public static void scenario(String title) {
        System.out.println();
        System.out.println("================ 场景: " + title + " ================");
    }

    public static void input(String label, Object value) {
        System.out.println("  [输入] " + label + " = " + value);
    }

    public static void info(String text) {
        System.out.println("  [过程] " + text);
    }

    public static void candidates(List<?> candidateStates) {
        System.out.println("  [候选实例状态] " + candidateStates);
    }

    public static void verdict(String text) {
        System.out.println("  [判定依据] " + text);
    }

    public static void pass(String assertion) {
        System.out.println("  [PASS] " + assertion);
    }
}
