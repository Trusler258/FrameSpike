package net.minecraft.command;

import java.util.List;

/**
 * 离线自检用的假 ICommand。方法名用真实 SRG 名，方法形态与 1.8.9 一致。
 * 注意：实现代码不依赖名字，只依赖形态。
 */
public interface ICommand extends Comparable<ICommand> {

    boolean func_71519_b(ICommandSender sender);

    void func_71515_b(ICommandSender sender, String[] args);

    List func_180525_a(ICommandSender sender, String[] args, Object pos);

    List func_71514_a();

    boolean func_82358_a(String[] args, int index);

    String func_71517_b();

    String func_71518_a(ICommandSender sender);
}
