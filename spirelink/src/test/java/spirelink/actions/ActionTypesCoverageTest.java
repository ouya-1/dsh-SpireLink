package spirelink.actions;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * I4 的静态兜底：{@code ActionTypes} 里声明的每个动作类型，都必须在 {@code ActionExecutor} 里被引用。
 *
 * <p>只要有一个类型「声明了、planner 也可能发、executor 却没有任何分支」，那一拍 {@code legal_actions}
 * 给出的动作就一定执行不了 —— 这正是 I4 禁止的情况。{@code purge} 就是这么被发现的一个死常量
 * （声明了没人处理，planner 也从没发过）。
 *
 * <p>这是源码级检查：直接读同仓库的两个 java 文件，不依赖运行时。判据是「字段名在 executor 源码里
 * 出现过」，比「类型字符串出现过」更严格 —— 字符串可能只出现在注释里。
 */
public class ActionTypesCoverageTest {

    private static String read(String relative) throws IOException {
        // surefire 的工作目录是模块根目录（spirelink/），但为了本地 IDE 里也能跑，往上找两级。
        File dir = new File(System.getProperty("user.dir"));
        File file = null;
        for (int i = 0; i < 4 && dir != null; i++) {
            File candidate = new File(dir, relative);
            if (candidate.isFile()) {
                file = candidate;
                break;
            }
            dir = dir.getParentFile();
        }
        if (file == null) {
            throw new IOException("找不到 " + relative);
        }
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void everyDeclaredActionTypeIsHandledByTheExecutor() throws Exception {
        String executor = read("src/main/java/spirelink/actions/ActionExecutor.java");
        List<String> missing = new ArrayList<String>();
        for (Field field : ActionTypes.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != String.class
                    || !Modifier.isPublic(field.getModifiers())) {
                continue;
            }
            String name = field.getName();
            // 必须按「完整标识符」匹配：PURGE 是 PURGE_CARD 的前缀，用 contains 会漏判
            // （这个坑是实测出来的：先加回 PURGE，测试居然还是绿的）。
            java.util.regex.Pattern ref =
                    java.util.regex.Pattern.compile("ActionTypes\\." + name + "\\b");
            if (!ref.matcher(executor).find()) {
                missing.add(name + "=\"" + field.get(null) + "\"");
            }
        }
        if (!missing.isEmpty()) {
            fail("这些动作类型声明了但 ActionExecutor 没有处理（I4 风险）: " + missing);
        }
        assertTrue("ActionTypes 应该有不少于 30 个类型", ActionTypes.class.getDeclaredFields().length >= 30);
    }
}
