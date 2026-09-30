package g2s.core;

/** 一段注释（行注释或块注释）在源文件中的位置与原文。 */
public final class CommentSpan {
    public final String kind;
    public final String text;
    public final int startLine;
    public final int endLine;

    public CommentSpan(String kind, String text, int startLine, int endLine) {
        this.kind = kind;
        this.text = text;
        this.startLine = startLine;
        this.endLine = endLine;
    }
}
