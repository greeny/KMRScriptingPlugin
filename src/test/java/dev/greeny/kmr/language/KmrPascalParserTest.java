package dev.greeny.kmr.language;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.ParsingTestCase;
import dev.greeny.kmr.language.psi.KmrPascalAdditiveExpression;
import dev.greeny.kmr.language.psi.KmrPascalAssignment;
import dev.greeny.kmr.language.psi.KmrPascalComparisonExpression;
import dev.greeny.kmr.language.psi.KmrPascalExpression;
import dev.greeny.kmr.language.psi.KmrPascalCallExpression;
import dev.greeny.kmr.language.psi.KmrPascalExpressionStatement;
import dev.greeny.kmr.language.psi.KmrPascalMultiplicativeExpression;
import dev.greeny.kmr.language.psi.KmrPascalProcedureDeclaration;
import dev.greeny.kmr.language.psi.KmrPascalUnaryExpression;
import dev.greeny.kmr.language.psi.KmrPascalWithStatement;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

public class KmrPascalParserTest extends ParsingTestCase
{

	public KmrPascalParserTest()
	{
		super("parser", "script", new KmrPascalParserDefinition());
	}

	@Override
	protected String getTestDataPath()
	{
		return "src/test/testData";
	}

	@Override
	protected boolean skipSpaces()
	{
		return true;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// valid code
	// ---------------------------------------------------------------------------------------------------------------

	public void testSampleScriptHasNoErrors() throws IOException
	{
		PsiFile file = parse(loadFile("Sample.script"));
		assertNoErrors(file);
		assertEquals(3, PsiTreeUtil.findChildrenOfType(file, KmrPascalProcedureDeclaration.class).size());
	}

	public void testWithStatement()
	{
		PsiFile file = parse("procedure A; begin with X, Y[1].Z do Foo(1); with Q do begin end; end;");
		assertNoErrors(file);
		assertEquals(2, PsiTreeUtil.findChildrenOfType(file, KmrPascalWithStatement.class).size());
	}

	public void testBitOperators()
	{
		// xor binds like or, shl/shr like div: (a xor (b shl 1)) = ((c shr 2) and 3)
		PsiFile file = parse("procedure A; begin x := a xor b shl 1 = c shr 2 and 3; end;");
		assertNoErrors(file);
		KmrPascalComparisonExpression comparison = PsiTreeUtil.findChildOfType(file, KmrPascalComparisonExpression.class);
		assertNotNull(comparison);
		assertEquals("a xor b shl 1", comparison.getExpressionList().get(0).getText());
		assertEquals("c shr 2 and 3", comparison.getExpressionList().get(1).getText());
		KmrPascalAdditiveExpression additive = (KmrPascalAdditiveExpression) comparison.getExpressionList().get(0);
		assertEquals("b shl 1", additive.getExpressionList().get(1).getText());
	}

	public void testRepeatWithSeveralStatements()
	{
		assertNoErrors(parse("procedure A; begin repeat a; b; c until x; repeat until y; end;"));
	}

	public void testCaseStatement()
	{
		assertNoErrors(parse("""
			procedure A;
			begin
			  case x of
			    1: a;
			    2, 3: begin b end;
			    4..6, -1: c;
			    7: ;
			    8: d
			    else e;
			  end;
			  case y of 1: z end;
			  case z of 1: a else begin b; c; end end;
			  case z of 1: a; 2: b else c end;
			end;
			"""));
	}

	public void testSemicolonsBetweenBlockStatementsAreOptional()
	{
		// PascalScript's block loop only skips semicolons; the missing-semicolon inspection warns about them
		PsiFile file = parse("procedure A; begin x := 1 y := 2 Foo(1) if x then y := 3 else z := 4; repeat Inc(i) i := i + 1 until i > 3 end;");
		assertNoErrors(file);
		assertEquals(5, PsiTreeUtil.findChildrenOfType(file, KmrPascalAssignment.class).size());
	}

	public void testOneLinerMustBeFollowedBySemicolonElseOrEnd()
	{
		// the game rejects anything else after the single statement of then/else/do and of a case branch
		assertNoErrors(parse("procedure A; begin if x then y := 1; if x then y := 1 else z := 2; while x do Inc(i); for i := 0 to 3 do Foo(i) end;"));
		assertNoErrors(parse("procedure A; begin if x then if y then a else b else c; if x then begin end else Foo; end;"));
		assertErrorCount(parse("procedure A; begin if x then y := 1 z := 2; end;"), 1);
		assertErrorCount(parse("procedure A; begin repeat if x then Break until y; end;"), 1);
		assertErrorCount(parse("procedure A; begin case x of 1: a 2: b end; end;"), 1);
		// several statements after the else of a case parse (the statement inspection reports them)
		assertNoErrors(parse("procedure A; begin case x of 1: a else e; f; end; end;"));
	}

	public void testErrorMessagesUseTokenText()
	{
		List<PsiErrorElement> errors = errors(parse("procedure A; begin if x then y := 1 z := 2; Foo(1 2); end;"));
		assertEquals(2, errors.size());
		assertEquals("';', else or end expected, got 'z'", errors.get(0).getErrorDescription());
		assertEquals("')' or ',' expected, got '2'", errors.get(1).getErrorDescription());
	}

	public void testCallsWithoutArgumentList()
	{
		PsiFile file = parse("procedure A; begin Foo; Actions.Bar; Baz(); end;");
		assertNoErrors(file);
		assertEquals(3, PsiTreeUtil.findChildrenOfType(file, KmrPascalExpressionStatement.class).size());
		assertEquals(1, PsiTreeUtil.findChildrenOfType(file, KmrPascalCallExpression.class).size());
	}

	public void testMultiDimensionalIndexing()
	{
		assertNoErrors(parse("procedure A; begin a[1, 2] := b[3][4] + c[i, j, k].d; end;"));
	}

	public void testLiterals()
	{
		assertNoErrors(parse("""
			const
			  A = $FF;
			  B = 1.5;
			  C = 1e10;
			  D = 2.5E-3;
			  E = #13#10;
			  F = 'a'#13'b';
			  G = 'it''s';
			  H = '';
			  I = nil;
			  J = not true;
			  K = -1 + +2;
			"""));
	}

	public void testDeclarations()
	{
		assertNoErrors(parse("""
			type
			  TRec = record a, b: Integer; c: array of Byte; end;
			  TEnum = (eA, eB);
			  TArr = array [0..MAX-1, TEnum] of TRec;
			  TDyn = array of array of Integer;
			  TProc = procedure;
			  TProc2 = procedure(a: Integer; var b: Integer);
			  TFunc = function(const a, b: Integer): Boolean;
			  TRange = 1..10;
			  TEnumSet = set of TEnum;
			  TByteSet = set of Byte;
			  TSmallSet = set of 0..7;
			var
			  x, y: Integer;
			  z: TArr;
			procedure P(a: Integer; var b: Integer; const c, d: Integer; out e: Integer);
			const Q = 1;
			var R: Integer;
			type T = Integer;
			begin
			end;
			function F: Integer;
			begin
			  Result := 1;
			  if eA in [eA, eB] then Exit;
			end;
			"""));
	}

	public void testLongProcedureDoesNotHitRecursionLimit()
	{
		StringBuilder sb = new StringBuilder("procedure A;\nbegin\n");
		for (int i = 0; i < 2000; i++) {
			sb.append("  x := x + ").append(i).append(";\n");
		}
		sb.append("end;\n");
		assertNoErrors(parse(sb.toString()));
	}

	public void testLongExpressionDoesNotHitRecursionLimit()
	{
		StringBuilder sb = new StringBuilder("procedure A;\nbegin\n  x := 0");
		for (int i = 0; i < 1000; i++) {
			sb.append(" + ").append(i);
		}
		sb.append(";\nend;\n");
		assertNoErrors(parse(sb.toString()));
	}

	// ---------------------------------------------------------------------------------------------------------------
	// precedence
	// ---------------------------------------------------------------------------------------------------------------

	public void testPrecedence()
	{
		PsiFile file = parse("procedure A; begin x := a + b * c = d; end;");
		assertNoErrors(file);

		KmrPascalComparisonExpression cmp = PsiTreeUtil.findChildOfType(file, KmrPascalComparisonExpression.class);
		assertNotNull(cmp);
		List<KmrPascalExpression> cmpOperands = cmp.getExpressionList();
		assertEquals(2, cmpOperands.size());
		assertInstanceOf(cmpOperands.get(0), KmrPascalAdditiveExpression.class);
		assertEquals("d", cmpOperands.get(1).getText());

		List<KmrPascalExpression> addOperands = ((KmrPascalAdditiveExpression) cmpOperands.get(0)).getExpressionList();
		assertEquals("a", addOperands.get(0).getText());
		assertInstanceOf(addOperands.get(1), KmrPascalMultiplicativeExpression.class);
		assertEquals("b * c", addOperands.get(1).getText());
	}

	public void testUnaryBindsTighterThanBinary()
	{
		PsiFile file = parse("procedure A; begin x := not a and -b; end;");
		assertNoErrors(file);
		KmrPascalMultiplicativeExpression and = PsiTreeUtil.findChildOfType(file, KmrPascalMultiplicativeExpression.class);
		assertNotNull(and);
		assertInstanceOf(and.getExpressionList().get(0), KmrPascalUnaryExpression.class);
		assertEquals("not a", and.getExpressionList().get(0).getText());
		assertEquals("-b", and.getExpressionList().get(1).getText());
	}

	// ---------------------------------------------------------------------------------------------------------------
	// error recovery
	// ---------------------------------------------------------------------------------------------------------------

	public void testMissingExpressionRecoversWithinStatement()
	{
		PsiFile file = parse("procedure A; begin x := ; y := 1; end; procedure B; begin end;");
		assertErrorCount(file, 1);
		assertEquals(2, PsiTreeUtil.findChildrenOfType(file, KmrPascalProcedureDeclaration.class).size());
	}

	public void testGarbageInsideStatementDoesNotBreakFollowingStatements()
	{
		PsiFile file = parse("procedure A; begin x := 1 + ) 2; Foo(1); end; procedure B; begin end;");
		assertErrorCount(file, 1);
		assertEquals(2, PsiTreeUtil.findChildrenOfType(file, KmrPascalProcedureDeclaration.class).size());
		assertEquals(1, PsiTreeUtil.findChildrenOfType(file, KmrPascalCallExpression.class).size());
	}

	public void testMissingEndDoesNotSwallowNextProcedure()
	{
		PsiFile file = parse("procedure A; begin if x then begin y; end; procedure B; begin z; end;");
		assertFalse(errors(file).isEmpty());
		Collection<KmrPascalProcedureDeclaration> procedures = PsiTreeUtil.findChildrenOfType(file, KmrPascalProcedureDeclaration.class);
		assertEquals(2, procedures.size());
		assertTrue(procedures.stream().anyMatch(p -> p.getText().startsWith("procedure B") && p.getText().endsWith("end;")));
	}

	public void testGarbageAtTopLevelDoesNotBreakFollowingDeclarations()
	{
		PsiFile file = parse("begin end; procedure B; begin end; 123 var x: Integer; procedure C; begin end;");
		assertErrorCount(file, 2);
		assertEquals(2, PsiTreeUtil.findChildrenOfType(file, KmrPascalProcedureDeclaration.class).size());
	}

	public void testBrokenVarDeclarationRecoversWithinBlock()
	{
		PsiFile file = parse("var a: ; b: Integer; procedure P; begin end;");
		assertErrorCount(file, 1);
		assertEquals(1, PsiTreeUtil.findChildrenOfType(file, KmrPascalProcedureDeclaration.class).size());
	}

	// ---------------------------------------------------------------------------------------------------------------
	// helpers
	// ---------------------------------------------------------------------------------------------------------------

	private PsiFile parse(String text)
	{
		myFile = createPsiFile("test", text);
		ensureParsed(myFile);
		return myFile;
	}

	private static List<PsiErrorElement> errors(PsiElement root)
	{
		return PsiTreeUtil.collectElementsOfType(root, PsiErrorElement.class).stream()
			.sorted((a, b) -> a.getTextOffset() - b.getTextOffset())
			.collect(Collectors.toList());
	}

	private static void assertNoErrors(PsiFile file)
	{
		assertErrorCount(file, 0);
	}

	private static void assertErrorCount(PsiFile file, int expected)
	{
		List<PsiErrorElement> errors = errors(file);
		if (errors.size() != expected) {
			StringBuilder sb = new StringBuilder("Expected " + expected + " parse errors, got " + errors.size() + ":\n");
			for (PsiErrorElement e : errors) {
				sb.append("  at ").append(e.getTextOffset()).append(": ").append(e.getErrorDescription())
					.append("  near '").append(near(file, e.getTextOffset())).append("'\n");
			}
			sb.append("\n").append(toParseTreeText(file, true, false));
			fail(sb.toString());
		}
	}

	private static String near(PsiFile file, int offset)
	{
		String text = file.getText();
		return text.substring(Math.max(0, offset - 15), Math.min(text.length(), offset + 15)).replace("\n", "\\n");
	}

}
