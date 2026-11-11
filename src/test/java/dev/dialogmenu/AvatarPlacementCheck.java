package dev.dialogmenu;

/** 依据 26.2 客户端对象字形的实际顶部和 advance 校验布局边界。 */
public final class AvatarPlacementCheck {
    private static void rejects(int top, int height) {
        try {
            SpriteCanvas.playerHeadRow(top, height, (height + 8) / 9);
            throw new AssertionError("接受了越界的头像行：" + top + ", height=" + height);
        } catch (IllegalArgumentException expected) { }
    }
    public static void main(String[] args) {
        if (SpriteCanvas.playerHeadRow(8, 30, 4) != 1) throw new AssertionError("首个完整可见行");
        if (SpriteCanvas.playerHeadRow(16, 30, 4) != 2) throw new AssertionError("最近行");
        if (SpriteCanvas.playerHeadRow(22, 36, 4) != 3) throw new AssertionError("末个完整可见行");
        rejects(0, 30);
        rejects(22, 30);
        System.out.println("PASS 头像行边界检查 5 项（离线，不代表客户端显示）");
    }
}
