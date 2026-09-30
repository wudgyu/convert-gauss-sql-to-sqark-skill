package g2s.core;

import java.util.ArrayList;
import java.util.List;

/** 一条被切分出来的语句及其全部定位信息。 */
public final class Statement {

    public String statementId;
    public int index;
    public String kind;
    public String sql;
    public String text;
    public int startLine;
    public int endLine;
    public int startChar;
    public int endChar;
    public long startByte;
    public long endByte;
    public String sha256;
    public List<CommentSpan> leadingComments = new ArrayList<>();
    public List<CommentSpan> trailingComments = new ArrayList<>();
    public boolean hasDollarQuote;
    public boolean isPlBlock;
    public boolean isMetaCommand;
    public String splitConfidence = "exact";
    public List<String> warnings = new ArrayList<>();
}
