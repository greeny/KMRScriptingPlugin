package dev.greeny.kmr.language;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.PsiPolyVariantReference;
import com.intellij.psi.PsiReference;
import com.intellij.psi.ResolveResult;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import dev.greeny.kmr.language.psi.*;

/** Name resolution inside one file. */
public class KmrPascalResolveTest extends BasePlatformTestCase
{

	public void testLocalVariable()
	{
		PsiElement target = resolve("procedure P; var Counter: Integer; begin coun<caret>ter := 1; end;");
		assertInstanceOf(target, KmrPascalVarIdentifier.class);
		assertEquals("Counter", ((PsiNamedElement) target).getName());
	}

	public void testParameter()
	{
		PsiElement target = resolve("procedure P(aIndex: Integer; var aOut: Integer); begin aOut := aInd<caret>ex; end;");
		assertInstanceOf(target, KmrPascalParameterIdentifier.class);
	}

	public void testGlobalVariableAndConstant()
	{
		assertInstanceOf(resolve("var G: Integer; procedure P; begin <caret>G := 1; end;"), KmrPascalVarIdentifier.class);
		assertInstanceOf(resolve("const C = 1; procedure P; begin x := <caret>C; end;"), KmrPascalConstantDeclaration.class);
	}

	public void testLocalShadowsGlobal()
	{
		PsiElement target = resolve("var X: Integer; procedure P; var X: Boolean; begin <caret>X := True; end;");
		assertInstanceOf(target, KmrPascalVarIdentifier.class);
		assertEquals("X: Boolean", target.getParent().getText().trim().replace(";", ""));
	}

	public void testProcedureCallAndResult()
	{
		assertInstanceOf(resolve("procedure Helper; begin end; procedure P; begin Hel<caret>per; end;"), KmrPascalProcedureDeclaration.class);
		assertInstanceOf(resolve("function F: Integer; begin Res<caret>ult := 1; end;"), KmrPascalFunctionDeclaration.class);
		assertInstanceOf(resolve("function F: Integer; begin <caret>F := 1; end;"), KmrPascalFunctionDeclaration.class);
	}

	public void testEnumValueAndTypeReference()
	{
		assertInstanceOf(resolve("type TColor = (cRed, cGreen); procedure P; begin x := cGre<caret>en; end;"), KmrPascalEnumValue.class);
		assertInstanceOf(resolve("type TColor = (cRed, cGreen); var c: TCol<caret>or;"), KmrPascalTypeDeclaration.class);
		assertNull(resolve("var c: Inte<caret>ger;"));
	}

	public void testRecordFieldThroughDot()
	{
		PsiElement target = resolve("type TRec = record X, Y: Integer; end; var R: TRec; procedure P; begin R.<caret>Y := 1; end;");
		assertInstanceOf(target, KmrPascalFieldIdentifier.class);
		assertEquals("Y", ((PsiNamedElement) target).getName());
	}

	public void testRecordFieldThroughArrayAndAlias()
	{
		String text = """
			type
			  TRec = record X: Integer; end;
			  TRecs = array of TRec;
			  TGrid = array [0..3, 0..3] of TRec;
			var
			  List: TRecs;
			  Grid: TGrid;
			  Nested: array of array of TRec;
			procedure P;
			begin
			  List[1].<caret>X := 1;
			end;
			""";
		assertInstanceOf(resolve(text), KmrPascalFieldIdentifier.class);
		assertInstanceOf(resolve(text.replace("List[1].<caret>X", "Grid[1, 2].<caret>X")), KmrPascalFieldIdentifier.class);
		assertInstanceOf(resolve(text.replace("List[1].<caret>X", "Nested[1][2].<caret>X")), KmrPascalFieldIdentifier.class);
		assertNull(resolve(text.replace("List[1].<caret>X", "List.<caret>X")));
	}

	public void testWithStatement()
	{
		String text = """
			type TRec = record X: Integer; end;
			var R: TRec; X: Boolean;
			procedure P;
			begin
			  with R do <caret>X := 1;
			end;
			""";
		assertInstanceOf(resolve(text), KmrPascalFieldIdentifier.class);
		// the subject of the with itself is not in the record scope
		assertInstanceOf(resolve(text.replace("with R do <caret>X", "with <caret>R do X")), KmrPascalVarIdentifier.class);
	}

	public void testUnknownIdentifierIsSimplyUnresolved()
	{
		PsiReference reference = referenceAt("procedure P; begin Nob<caret>ody.ShowMsg(0, 'x'); end;");
		assertNotNull(reference);
		assertNull(reference.resolve());
		assertEquals(0, ((PsiPolyVariantReference) reference).multiResolve(false).length);
	}

	public void testRenameUpdatesAllCaseVariants()
	{
		myFixture.configureByText("a.script", """
			var Counter: Integer;
			procedure P;
			begin
			  coun<caret>ter := 1;
			  COUNTER := Counter + 1;
			end;
			""");
		myFixture.renameElementAtCaret("Total");
		myFixture.checkResult("""
			var Total: Integer;
			procedure P;
			begin
			  Total := 1;
			  Total := Total + 1;
			end;
			""");
	}

	public void testFindUsagesIsCaseInsensitive()
	{
		myFixture.configureByText("a.script", """
			var Coun<caret>ter: Integer;
			procedure P;
			begin
			  counter := 1;
			  COUNTER := Counter + 1;
			end;
			""");
		assertEquals(3, myFixture.findUsages(myFixture.getElementAtCaret()).size());
	}

	// ---------------------------------------------------------------------------------------------------------------

	private PsiElement resolve(String text)
	{
		PsiReference reference = referenceAt(text);
		assertNotNull("no reference at caret", reference);
		if (reference instanceof PsiPolyVariantReference) {
			ResolveResult[] results = ((PsiPolyVariantReference) reference).multiResolve(false);
			assertTrue("ambiguous: " + results.length + " results", results.length <= 1);
		}
		return reference.resolve();
	}

	private PsiReference referenceAt(String text)
	{
		PsiFile file = myFixture.configureByText("a.script", text);
		return file.findReferenceAt(myFixture.getCaretOffset());
	}

}
