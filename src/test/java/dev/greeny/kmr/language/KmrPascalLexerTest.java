package dev.greeny.kmr.language;

import com.intellij.lexer.Lexer;
import com.intellij.testFramework.LexerTestCase;
import org.jetbrains.annotations.NotNull;

public class KmrPascalLexerTest extends LexerTestCase
{

	@Override
	protected @NotNull Lexer createLexer()
	{
		return new KmrPascalLexerAdapter();
	}

	@Override
	protected @NotNull String getDirPath()
	{
		return "src/test/testData/lexer";
	}

	public void testNumbers()
	{
		doTest("1 1.5 1e5 2.5E-3 $FF 1..2",
			tokens(
				"NUMBER ('1')", "WHITE_SPACE (' ')",
				"NUMBER ('1.5')", "WHITE_SPACE (' ')",
				"NUMBER ('1e5')", "WHITE_SPACE (' ')",
				"NUMBER ('2.5E-3')", "WHITE_SPACE (' ')",
				"NUMBER ('$FF')", "WHITE_SPACE (' ')",
				"NUMBER ('1')", ".. ('..')", "NUMBER ('2')"
			));
	}

	public void testStringsAndChars()
	{
		doTest("'a' 'it''s' '' #13#$0A'x'",
			tokens(
				"STRING (''a'')", "WHITE_SPACE (' ')",
				"STRING (''it''s'')", "WHITE_SPACE (' ')",
				"STRING ('''')", "WHITE_SPACE (' ')",
				"CHAR ('#13')", "CHAR ('#$0A')", "STRING (''x'')"
			));
	}

	public void testUnterminatedStringStopsAtLineEnd()
	{
		doTest("'abc\nx",
			tokens(
				"STRING (''abc')", "WHITE_SPACE ('\\n')", "IDENTIFIER ('x')"
			));
	}

	public void testCommentsAndDirectives()
	{
		doTest("{ a } (* b *) // c\n{$I x} (*) {$D",
			tokens(
				"COMMENT_A ('{ a }')", "WHITE_SPACE (' ')",
				"COMMENT_B ('(* b *)')", "WHITE_SPACE (' ')",
				"COMMENT_A ('// c')", "WHITE_SPACE ('\\n')",
				"DIRECTIVE_INCLUDE ('{$I x}')", "WHITE_SPACE (' ')",
				// "(*)" does not close the comment, so it runs to EOF and swallows the directive
				"COMMENT_B ('(*) {$D')"
			));
	}

	public void testUnterminatedDirectiveStopsAtLineEnd()
	{
		doTest("{$I x\ny", tokens("DIRECTIVE_INCLUDE ('{$I x')", "WHITE_SPACE ('\\n')", "IDENTIFIER ('y')"));
	}

	public void testDirectiveKinds()
	{
		doTest("{$INCLUDE a.script}{$define X}{$UNDEF X}{$IFDEF X}{$ifndef X}{$ELSE}{$ENDIF}{$EVENT evtTick:MyTick}{$R+}{$Ifoo}",
			tokens(
				"DIRECTIVE_INCLUDE ('{$INCLUDE a.script}')",
				"DIRECTIVE_DEFINE ('{$define X}')",
				"DIRECTIVE_UNDEF ('{$UNDEF X}')",
				"DIRECTIVE_IFDEF ('{$IFDEF X}')",
				"DIRECTIVE_IFNDEF ('{$ifndef X}')",
				"DIRECTIVE_ELSE ('{$ELSE}')",
				"DIRECTIVE_ENDIF ('{$ENDIF}')",
				"DIRECTIVE_EVENT ('{$EVENT evtTick:MyTick}')",
				"DIRECTIVE ('{$R+}')",
				"DIRECTIVE ('{$Ifoo}')"
			));
	}

	public void testKeywordsAreCaseInsensitive()
	{
		doTest("BEGIN Begin begin",
			tokens("begin ('BEGIN')", "WHITE_SPACE (' ')", "begin ('Begin')", "WHITE_SPACE (' ')", "begin ('begin')"));
	}

	public void testOperators()
	{
		doTest(":= <> <= >= < > = @",
			tokens(
				":= (':=')", "WHITE_SPACE (' ')",
				"<> ('<>')", "WHITE_SPACE (' ')",
				"<= ('<=')", "WHITE_SPACE (' ')",
				">= ('>=')", "WHITE_SPACE (' ')",
				"< ('<')", "WHITE_SPACE (' ')",
				"> ('>')", "WHITE_SPACE (' ')",
				"= ('=')", "WHITE_SPACE (' ')",
				"@ ('@')"
			));
	}

	private static String tokens(String... lines)
	{
		StringBuilder sb = new StringBuilder();
		for (String line : lines) {
			// token types print as they read in parser messages: keyword/operator tokens as their text ("begin", ":="),
			// the others as a word; the platform's WHITE_SPACE prints as is
			int space = line.indexOf(' ');
			String name = line.substring(0, space);
			String word = name.startsWith("DIRECTIVE") ? "directive" : switch (name) {
				case "IDENTIFIER" -> "identifier";
				case "NUMBER" -> "number";
				case "STRING" -> "string";
				case "CHAR" -> "character";
				case "COMMENT_A", "COMMENT_B" -> "comment";
				default -> name;
			};
			sb.append(word).append(line.substring(space)).append('\n');
		}
		return sb.toString();
	}

}
