package dev.greeny.kmr.language;

import com.intellij.codeInsight.highlighting.BraceMatchingUtil;
import com.intellij.openapi.editor.Editor;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public class KmrPascalBraceMatcherTest extends BasePlatformTestCase
{

	public void testBeginSkipsEndOfNestedCase()
	{
		assertMatches("""
			procedure A;
			<begin>
			  case x of
			    1: y;
			  end;
			<end>;
			""");
	}

	public void testCaseEndMatchesCase()
	{
		assertMatches("""
			procedure A;
			begin
			  <case> x of
			    1: begin y end;
			    else z;
			  <end>;
			end;
			""");
	}

	public void testRecordEnd()
	{
		assertMatches("type R = <record> a: Integer; end2: array of Integer; <end>;");
	}

	public void testNestedBegins()
	{
		assertMatches("""
			procedure A;
			begin
			  if x then <begin>
			    case y of 1: begin end; end;
			    begin end;
			  <end>;
			end;
			""");
	}

	public void testRepeatUntil()
	{
		assertMatches("""
			procedure A;
			begin
			  <repeat>
			    repeat x := x + 1 until x > 5;
			    case x of 1: begin end; end;
			  <until> done;
			end;
			""");
	}

	public void testConditionalDirectives()
	{
		assertMatches("""
			<{$IFNDEF guard}>
			{$DEFINE guard}
			{$IFDEF other}
			var x: Integer;
			{$ENDIF}
			<{$ENDIF}>
			""");
	}

	public void testBrackets()
	{
		assertMatches("procedure A; begin x := <(>a + (b * c)<)>; y := [1, [2]]; end;");
	}

	/**
	 * The text marks the opening brace as {@code <token>} and the closing one as {@code <end>} / {@code <)>};
	 * matching is checked in both directions.
	 */
	private void assertMatches(String marked)
	{
		int open = marked.indexOf('<');
		String openToken = marked.substring(open + 1, marked.indexOf('>', open));
		String s1 = marked.substring(0, open) + openToken + marked.substring(marked.indexOf('>', open) + 1);

		int close = s1.indexOf('<', open + openToken.length());
		String closeToken = s1.substring(close + 1, s1.indexOf('>', close));
		String text = s1.substring(0, close) + closeToken + s1.substring(s1.indexOf('>', close) + 1);

		myFixture.configureByText("a.script", text);
		Editor editor = myFixture.getEditor();

		editor.getCaretModel().moveToOffset(open);
		assertEquals("forward match of '" + openToken + "'", close,
			BraceMatchingUtil.getMatchedBraceOffset(editor, true, myFixture.getFile()));

		editor.getCaretModel().moveToOffset(close);
		assertEquals("backward match of '" + closeToken + "'", open,
			BraceMatchingUtil.getMatchedBraceOffset(editor, false, myFixture.getFile()));
	}

}
