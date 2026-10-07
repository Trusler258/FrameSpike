package net.minecraft.command;

import net.minecraft.util.text.ITextComponent;

/** 离线自检用的假 ICommandSender。真实接口只有一个「单参 + void」方法，就是加聊天消息。 */
public interface ICommandSender {

    String func_70005_c_();

    void func_145747_a(ITextComponent component);
}
