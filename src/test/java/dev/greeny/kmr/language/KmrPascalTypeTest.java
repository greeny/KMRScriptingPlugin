package dev.greeny.kmr.language;

import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import dev.greeny.kmr.language.inspections.KmrPascalTypeCheckInspection;
import dev.greeny.kmr.language.psi.KmrPascalAssignment;
import dev.greeny.kmr.language.psi.KmrPascalExpression;
import dev.greeny.kmr.language.types.KmrType;
import dev.greeny.kmr.language.types.KmrTypeProvider;

import java.nio.file.Files;
import java.nio.file.Path;

/** Type inference and the type-check inspection. */
public class KmrPascalTypeTest extends BasePlatformTestCase
{

	private static final String DECLARATIONS = """
		type
		  TColor = (cRed, cGreen);
		  TRec = record X: Integer; Name: String; end;
		  TInts = array of Integer;
		  TColors = set of TColor;
		const
		  C_INT = 5; C_STR = 'abc'; C_REAL = 1.5;
		var
		  I: Integer; B: Byte; R: Single; S: String; Ch: Char; Bo: Boolean; Col: TColor; Rec: TRec; Ints: TInts; Cols: TColors;
		  Grid: array [0..3] of TRec; Cb: procedure(a: Integer);
		function F(a: Integer): Boolean; begin Result := a > 1; end;
		procedure P; begin end;
		""";

	public void testInferredTypes()
	{
		assertType("Integer", "I + B");
		assertType("Integer", "$FF div 2");
		assertType("Integer", "I / 2"); // no PS_DELPHIDIV in the game's PascalScript: integer division
		assertType("Single", "I / 2.0");
		assertType("TColor", "Col - 1"); // the KaM fork: enum minus integer stays the enum
		assertType("Single", "I + R");
		assertType("String", "S + 'x'");
		assertType("String", "'a' + 'b'");
		assertType("Char", "'a'");
		assertType("Char", "S[1]");
		assertType("Boolean", "I > 1");
		assertType("Boolean", "not Bo");
		assertType("Integer", "not I");
		assertType("Integer", "I xor B");
		assertType("Boolean", "Bo xor (I > 1)");
		assertType("Integer", "I shl 2");
		assertType("Integer", "I shr 1");
		assertType("Boolean", "cRed in Cols");
		assertType("TColor", "Col");
		assertType("TColor", "cGreen");
		assertType("TColor", "TColor(1)");
		assertType("Integer", "Integer(Col)");
		assertType("TRec", "Rec");
		assertType("Integer", "Rec.X");
		assertType("String", "Grid[1].Name");
		assertType("Integer", "Ints[0]");
		assertType("array of Integer", "Ints");
		assertType("set of TColor", "Cols + [cRed]");
		assertType("Boolean", "F(1)");
		assertType("no value (procedure call)", "P()");
		assertType("Integer", "Length(S)");
		assertType("String", "IntToStr(3)");
		assertType("Single", "Abs(I)"); // PascalScript's Abs is Extended -> Extended (Utils.AbsI for integers)
		assertType("Integer", "Round(R)");
		assertType("Variant", "High(Ints)");
		assertType("Char", "StrGet(S, 1)");
		assertType("Boolean", "VarIsNull(I)");
		assertType("Single", "Pi");
		assertType("unknown", "Random(5)"); // not part of the game's PascalScript
		assertType("Integer", "C_INT");
		assertType("String", "C_STR");
		assertType("Single", "C_REAL");
		assertType("Cardinal", "States.GameTime", "Integer");
		assertType("TKMUnitType", "States.UnitTypeEx(1)");
		assertType("TKMPoint", "States.HousePosition(1)");
		assertType("Integer", "States.HousePosition(1).X");
		assertType("unknown", "Unknown");
		assertType("unknown", "Unknown + 1");
		assertType("procedural type", "@P");
		assertType("nil", "nil");
	}

	public void testTypeCheckInspection()
	{
		myFixture.enableInspections(new KmrPascalTypeCheckInspection());
		myFixture.configureByText("a.script", DECLARATIONS + """
			procedure OnTick;
			var J: Integer;
			begin
			  I := B; R := I; S := Ch; I := I div 2; Bo := I > 1; Col := cRed; Col := TColor(1); Ints := [1, 2]; Cols := [cRed]; Rec := Grid[0];
			  I := <error descr="Cannot assign Single to Integer">R</error>;
			  I := <error descr="Cannot assign String to Integer">S</error>;
			  Col := 1; I := Col; // the KaM fork accepts enum <-> integer in assignments (not in arguments)
			  I := Ints[High(Ints)]; Ch := S[Length(S)]; case High(Ints) of 0: Exit; end; for I := Low(Ints) to High(Ints) do Exit;
			  Bo := <error descr="Cannot assign Integer to Boolean">I</error>;
			  Ints := <error descr="Cannot assign [...] to array of Integer">['a']</error>;
			  Rec := <error descr="Cannot assign Integer to TRec">1</error>;
			  I := <error descr="Cannot assign no value (procedure call) to Integer">P()</error>;
			  Actions.GiveUnitEx(0, <error descr="Argument 'aType' of 'GiveUnitEx' expects TKMUnitType, got Integer">1</error>, 1, 1, dirN);
			  Actions.GiveUnitEx(0, utSerf, 1, 1, dirN);
			  Actions.ShowMsg(0, <error descr="Argument 'aText' of 'ShowMsg' expects String, got Integer">5</error>);
			  Actions.ShowMsg(0, 'ok' + IntToStr(I));
			  Utils.Format('%d', [I, S]);
			  if <error descr="Condition must be Boolean, got Integer">I</error> then Exit;
			  while Bo and (I > 0) do Dec(I);
			  repeat Inc(I) until <error descr="Condition must be Boolean, got String">S</error>;
			  J := <error descr="Operator '+' cannot be applied to String and Integer">S + 1</error>;
			  Bo := <error descr="Operator 'and' cannot be applied to Boolean and Integer">Bo and I</error>;
			  J := <error descr="Operator 'shl' cannot be applied to Integer and Single">I shl R</error>;
			  J := I shl 1 + I shr 2; Bo := Bo xor (I = 1);
			  Bo := <error descr="Operator '=' cannot be applied to TColor and Integer">Col = 1</error>;
			  Bo := <error descr="Operator 'in' cannot be applied to Integer and Integer">I in J</error>;
			  Bo := <error descr="Operator '<' cannot be applied to Boolean and Integer">Bo < 1</error>;
			  J := <error descr="Operator '-' cannot be applied to String">-S</error>;
			  J := <error descr="Cannot index a value of type Integer">I</error>[1];
			  J := Ints[<error descr="Index must be an ordinal value, got String">S</error>];
			  for J := 0 to 3 do Ints[J] := J;
			  for J := 0 to <error descr="Loop bound must be Integer, got String">S</error> do Exit;
			  for <error descr="Loop variable must be an ordinal type, got String">S</error> := 0 to 3 do Exit;
			  case Col of cRed: Exit; <error descr="Case label must be TColor, got Integer">2</error>: Exit; end;
			  case I of 1, 2..3: Exit; end;
			  with Rec do X := 1;
			  with <error descr="'with' requires a record, got Integer">I</error> do Exit;
			  Cb := @P; Cb := nil;
			  J := Unknown + 1;
			end;
			""");
		myFixture.checkHighlighting();
	}

	public void testNoFalsePositivesOnSampleScript() throws Exception
	{
		myFixture.enableInspections(new KmrPascalTypeCheckInspection());
		myFixture.configureByText("Sample.script", Files.readString(Path.of("src/test/testData/parser/Sample.script")));
		myFixture.checkHighlighting();
	}

	// ---------------------------------------------------------------------------------------------------------------

	private void assertType(String expected, String expression)
	{
		assertType(expected, expression, expected);
	}

	/** The last assignment's right side is the expression under test; {@code alsoAcceptable} allows an alias display name. */
	private void assertType(String expected, String expression, String alsoAcceptable)
	{
		PsiFile file = myFixture.configureByText("a.script", DECLARATIONS + "procedure T; begin Dummy := " + expression + "; end;");
		KmrPascalAssignment assignment = PsiTreeUtil.findChildrenOfType(file, KmrPascalAssignment.class).stream().reduce((a, b) -> b).orElseThrow();
		KmrPascalExpression right = assignment.getExpressionList().get(1);
		KmrType type = KmrTypeProvider.typeOf(right);
		assertTrue(expression + " -> " + type, type.toString().equals(expected) || type.toString().equals(alsoAcceptable));
	}

}
