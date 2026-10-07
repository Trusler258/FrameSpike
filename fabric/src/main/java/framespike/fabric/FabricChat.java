package framespike.fabric;

import framespike.Cmd;
import framespike.FrameSpike;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.File;

/**
 * Fabric 侧聊天出口。
 *
 * 核心的文案是给 1.8.9 写的，里面带一堆 §e / §r 这样的老式颜色码；现代 MC 的 Text 不认这些码，
 * 所以要逐段拆出来换成 Style。链接也一样：http(s) 走 OPEN_URL，本地文件走 OPEN_FILE
 * （报告就是一个本地 .html，OPEN_FILE 会直接拿浏览器打开它）。
 */
public class FabricChat implements Cmd.Sink {

    @Override
    public void send(String text) {
        send(text, null);
    }

    @Override
    public void sendLink(String text, String url) {
        send(text, url);
    }

    private void send(String text, String url) {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            ClientPlayerEntity player = mc == null ? null : mc.player;
            if (player == null) {
                // 还没进世界（主菜单之类）：聊天发不出去，至少别让消息凭空消失
                FrameSpike.note(text + (url == null ? "" : "   " + url));
                return;
            }
            MutableText msg = legacy(text);
            ClickEvent click = clickFor(url);
            if (click != null) {
                msg.setStyle(msg.getStyle().withClickEvent(click));
            }
            player.sendMessage(msg, false);
        } catch (Throwable t) {
            FrameSpike.note("[chat] Fabric 发送失败（消息内容见下一行）: " + t);
            FrameSpike.note(text + (url == null ? "" : "   " + url));
        }
    }

    /** §a 这类颜色码 -> Text。'&' 也认（配置文件里手写时更顺手）。 */
    static MutableText legacy(String s) {
        MutableText out = Text.empty();
        if (s == null) return out;
        MutableText buf = Text.empty();
        Formatting cur = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c == '\u00a7' || c == '&') && i + 1 < s.length()) {
                Formatting f = Formatting.byCode(s.charAt(i + 1));
                if (f != null) {
                    if (buf.getString().length() > 0) {
                        out.append(cur == null ? buf : buf.formatted(cur));
                        buf = Text.empty();
                    }
                    cur = f;
                    i++;
                    continue;
                }
            }
            buf.append(String.valueOf(c));
        }
        if (buf.getString().length() > 0) {
            out.append(cur == null ? buf : buf.formatted(cur));
        }
        return out;
    }

    /** http(s) -> OPEN_URL；其它（含 file:///）当本地路径 -> OPEN_FILE，让系统默认程序打开。 */
    static ClickEvent clickFor(String url) {
        if (url == null || url.length() == 0) return null;
        try {
            if (url.startsWith("http://") || url.startsWith("https://")) {
                return new ClickEvent(ClickEvent.Action.OPEN_URL, url);
            }
            String p = url;
            if (p.startsWith("file:///")) p = p.substring(8);
            else if (p.startsWith("file://")) p = p.substring(7);
            else if (p.startsWith("file:")) p = p.substring(5);
            p = p.replace('/', File.separatorChar);
            return new ClickEvent(ClickEvent.Action.OPEN_FILE, p);
        } catch (Throwable t) {
            return null;
        }
    }
}
