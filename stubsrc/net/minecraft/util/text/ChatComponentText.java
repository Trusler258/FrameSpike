package net.minecraft.util.text;

/** 离线自检用的假 ChatComponentText：只有一个单参 String 构造器，和运行时形态一致。 */
public class ChatComponentText implements ITextComponent {

    private final String text;

    public ChatComponentText(String text) {
        this.text = text;
    }

    public String func_150261_e() {
        return text;
    }

    public String toString() {
        return "ChatComponentText{" + text + "}";
    }
}
