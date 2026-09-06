package dev.greeny.kmr.language;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import dev.greeny.kmr.language.inspections.*;
import dev.greeny.kmr.language.unit.KmrPascalProjectSettings;

/** Structural inspections. Expected problems are marked inline with the test framework's tags. */
public class KmrPascalInspectionTest extends BasePlatformTestCase
{

	@Override
	protected void setUp() throws Exception
	{
		super.setUp();
		myFixture.enableInspections(
			new KmrPascalUnresolvedReferenceInspection(), new KmrPascalDeprecatedInspection(), new KmrPascalArgumentCountInspection(),
			new KmrPascalDuplicateDeclarationInspection(), new KmrPascalUnusedLocalInspection(), new KmrPascalEventHandlerInspection(),
			new KmrPascalPreprocessorInspection(), new KmrPascalIncludeGuardInspection(), new KmrPascalStatementInspection(),
			new KmrPascalMissingSemicolonInspection(), new KmrPascalIncDecInspection());
	}

	public void testUnresolvedIdentifiersAndMembers()
	{
		check("""
			var Known: Integer;
			procedure OnTick;
			var Local: TKMPoint;
			begin
			  Known := <warning descr="Unresolved identifier 'Unknown'">Unknown</warning> + Length('abc') + Local.X;
			  Actions.<warning descr="Unknown member 'Nope' of Actions">Nope</warning>(1);
			  <warning descr="Unresolved identifier 'Unknown2'">Unknown2</warning>.Whatever(1);
			  Local.<warning descr="Unknown member 'Z' of TKMPoint">Z</warning> := 1;
			end;
			var Bad: <warning descr="Unknown type 'TNope'">TNope</warning>;
			var Ok: Integer;
			""");
	}

	public void testMissingDelphiRoutinesGetAReplacementHint()
	{
		check("""
			procedure OnTick;
			var I: Integer; S: String;
			begin
			  I := <warning descr="Unresolved identifier 'Random' (not available in KaM Remake's PascalScript; use States.KaMRandom / States.KaMRandomI or Utils.RandomRangeI)">Random</warning>(5);
			  S := <warning descr="Unresolved identifier 'Format' (not available in KaM Remake's PascalScript; use Utils.Format)">Format</warning>('%d', [I]);
			  I := <warning descr="Unresolved identifier 'Min' (not available in KaM Remake's PascalScript; use Utils.MinI / Utils.MinS)">Min</warning>(I, 2);
			  I := Ord('a') + Length(S) + StrToIntDef(S, 0) + Round(Sqrt(2)) + Ord(Chr(65));
			  Inc(I); Dec(I); SetLength(S, 3); Delete(S, 1, 1);
			  S := IntToStr(I) + Copy(S, 1, 2) + UpperCase(S) + Trim(S) + PadL(S, 5);
			end;
			var <warning descr="'Length' redeclares a standard PascalScript identifier">Length</warning>: Integer;
			""");
	}

	public void testUnresolvedInOlderVersionMentionsOtherVersion()
	{
		KmrPascalProjectSettings.getInstance(getProject()).setDefaultStubVersion("r6720");
		PsiManager.getInstance(getProject()).dropPsiCaches();
		try {
			check("""
				procedure OnTick;
				begin
				  Actions.<warning descr="Unknown member 'GiveUnitEx' of Actions (available in game version r16020)">Give<caret>UnitEx</warning>(0, 1, 1, 1, 0);
				  <warning descr="Unresolved identifier 'Utils' (available in game version r16020)">Utils</warning>.AbsI(1);
				end;
				""");
			IntentionAction fix = myFixture.findSingleIntention("Switch game version to r16020");
			myFixture.launchAction(fix);
			assertEquals("r16020", KmrPascalProjectSettings.getInstance(getProject()).getDefaultStubVersion());
		} finally {
			KmrPascalProjectSettings.getInstance(getProject()).setDefaultStubVersion("r16020");
		}
	}

	public void testDeprecatedAndArgumentCount()
	{
		check("""
			// @deprecated Use Newer
			procedure Older(<weak_warning descr="Unused parameter 'a'">a</weak_warning>: Integer); begin end;
			procedure Newer(<weak_warning descr="Unused parameter 'a'">a</weak_warning>: Integer); begin end;
			procedure OnTick;
			begin
			  <warning descr="'Older' is deprecated: Use Newer">Older</warning>(1);
			  Actions.<warning descr="'GiveUnit' is deprecated: Use GiveUnitEx instead">GiveUnit</warning>(0, 1, 1, 1, 0);
			  Newer(1);
			  Newer<error descr="'Newer' expects 1 argument, got 2">(1, 2)</error>;
			  <error descr="'Newer' expects 1 argument, got 0">Newer</error>;
			  Actions.ShowMsg<error descr="'ShowMsg' expects 2 arguments, got 1">(0)</error>;
			  Length('x');
			end;
			""");
	}

	public void testDuplicateDeclarations()
	{
		check("""
			var <error descr="'X' is already declared">X</error>: Integer;
			const <error descr="'x' is already declared">x</error> = 1;
			var <warning descr="'Actions' redeclares a built-in identifier of the KaM Remake API">Actions</warning>: Integer;
			type TRec = record A: Integer; <error descr="'a' is already declared in this scope">a</error>: Byte; end;
			procedure P(a: Integer);
			var <error descr="'A' is already declared in this scope">A</error>: Integer;
			begin
			  A := a;
			end;
			""");
	}

	public void testDuplicateEventHandlerAcrossUnit()
	{
		myFixture.addFileToProject("main.dat", "");
		myFixture.addFileToProject("main.script", "{$I shared.script}\nprocedure OnTick; begin end;");
		myFixture.configureByText("shared.script", "procedure <error descr=\"Event handler 'OnTick' is declared more than once in the compilation unit of main.script (also in main.script)\">OnTick</error>; begin end;");
		myFixture.checkHighlighting();
	}

	public void testUnusedLocals()
	{
		myFixture.configureByText("a.script", """
			procedure OnHouseBuilt(aHouse: Integer);
			var <weak_warning descr="Unused local variable 'Unused'">Unused</weak_warning>: Integer; Used: Integer;
			begin
			  Used := 1;
			end;
			procedure Helper(<weak_warning descr="Unused parameter 'aX'">aX</weak_warning>, aY: Integer);
			begin
			  aY := 1;
			end;
			""");
		myFixture.checkHighlighting(true, false, true);
	}

	public void testEventHandlerSignatureWithFix()
	{
		check("""
			procedure OnTick; begin end;
			procedure <error descr="Event handler 'OnHouseBuilt' must be declared as 'procedure OnHouseBuilt(aHouse: Integer)'">OnHouse<caret>Built</error>(aHouse: Byte; aExtra: Integer);
			begin
			end;
			{$EVENT evtUnitDied:MyDied}
			procedure <error descr="Event handler 'MyDied' must be declared as 'procedure MyDied(aUnit: Integer; aKillerOwner: Integer)'">MyDied</error>;
			begin
			end;
			""");
		IntentionAction fix = myFixture.findSingleIntention("Change to 'procedure OnHouseBuilt(aHouse: Integer)'");
		myFixture.launchAction(fix);
		assertTrue(myFixture.getFile().getText(), myFixture.getFile().getText().contains("procedure OnHouseBuilt(aHouse: Integer);\nbegin\nend;"));
	}

	public void testPreprocessorProblems()
	{
		myFixture.addFileToProject("dup.script", "var D: Integer;");
		check("""
			<error descr="Cannot find included file 'missing.script'">{$I missing.script}</error>
			{$I dup.script}
			<warning descr="File 'dup.script' is included more than once with active content">{$I dup.script}</warning>
			<error descr="{$ENDIF} without {$IFDEF}/{$IFNDEF}">{$ENDIF}</error>
			{$EVENT evtHouseBuilt:<error descr="Procedure 'Missing' is not declared in the compilation unit">Missing</error>}
			{$EVENT <error descr="Unknown event 'evtNope'">evtNope</error>:OnTick}
			{$EVENT evtTick:OnTick}
			procedure OnTick; begin end;
			<error descr="Conditional directive is not closed by {$ENDIF} in this file">{$IFDEF x}</error>
			var Y: Integer;
			""");
	}

	public void testIncludeGuardWithFix()
	{
		myFixture.addFileToProject("a.dat", "");
		myFixture.addFileToProject("a.script", "{$I shared.script}");
		myFixture.addFileToProject("b.dat", "");
		myFixture.addFileToProject("b.script", "{$I shared.script}");
		myFixture.addFileToProject("guarded.script", "{$IFNDEF guardedScript}\n{$DEFINE guardedScript}\nvar G: Integer;\n{$ENDIF}\n");
		myFixture.addFileToProject("c.script", "{$I guarded.script}{$I guarded.script}");

		myFixture.configureByText("shared.script", "<weak_warning descr=\"This script is included from 2 places but has no include guard\">var Shared: Integer;</weak_warning>\n");
		myFixture.checkHighlighting(true, false, true);
		myFixture.launchAction(myFixture.findSingleIntention("Add include guard"));
		myFixture.checkResult("{$IFNDEF sharedScript}\n{$DEFINE sharedScript}\n\nvar Shared: Integer;\n{$ENDIF}\n");

		// a guarded file is fine even when included from several places
		myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("guarded.script"));
		myFixture.checkHighlighting(true, false, true);
	}

	public void testStatements()
	{
		check("""
			const C = 1;
			type TE = (eA, eB);
			var V, W: Integer;
			procedure P; begin end;
			function F: Integer; begin Result := 1; F := 2; end;
			procedure OnTick;
			var I: Integer;
			begin
			  <error descr="Cannot assign to constant 'C'">C</error> := 2;
			  <error descr="Cannot assign to constant 'eA'">eA</error> := eB;
			  <error descr="Cannot assign to procedure 'P'">P</error> := 1;
			  <error descr="Cannot assign to function 'F' outside of its body">F</error> := 1;
			  <error descr="Left side of an assignment must be a variable">1</error> := V;
			  <error descr="Left side of an assignment must be a variable">V + 1</error> := 2;
			  <error descr="Not a statement">V + 1</error>;
			  <error descr="Not a statement">1</error>;
			  for I := 0 to 3 do <weak_warning descr="Can be replaced with 'Inc(I)'"><warning descr="Assignment to loop variable 'I'">I</warning> := I + 1</weak_warning>;
			  case V of 1: P; else P; <error descr="The else part of a case statement takes one statement; use begin..end for several">W := 1</error>; end;
			  case V of 1: P; else begin P; W := 1; end; end;
			  V := W;
			  P;
			  F;
			  W := F;
			end;
			""");
	}

	public void testMissingSemicolonWithFix()
	{
		check("""
			var V, W, I: Integer;
			procedure P; begin end;
			procedure OnTick;
			begin
			  V := <warning descr="';' after statement is optional in PascalScript but recommended"><caret>1</warning>
			  W := 2;
			  <warning descr="';' after statement is optional in PascalScript but recommended">P</warning>
			  if V = 1 then W := 3 else W := 4;
			  if V = 1 then begin W := <warning descr="';' after statement is optional in PascalScript but recommended">5</warning> <warning descr="';' is optional before 'end' but recommended">P</warning> end
			  else P;
			  if V = 1 then P else W := 6;
			  repeat
			    Inc(I<warning descr="';' after statement is optional in PascalScript but recommended">)</warning>
			    W := <warning descr="';' is optional before 'until' but recommended">I</warning>
			  until I > 3;
			  case V of
			    1: P;
			    2: <warning descr="';' is optional before 'else' but recommended">P</warning>
			    else <warning descr="';' is optional before 'end' but recommended">P</warning>
			  end;
			  while V > 0 do Dec(V);
			  <warning descr="';' is optional before 'end' but recommended">P</warning>
			end;
			""");
		myFixture.launchAction(myFixture.findSingleIntention("Insert ';'"));
		myFixture.checkResult("""
			var V, W, I: Integer;
			procedure P; begin end;
			procedure OnTick;
			begin
			  V := 1;
			  W := 2;
			  P
			  if V = 1 then W := 3 else W := 4;
			  if V = 1 then begin W := 5 P end
			  else P;
			  if V = 1 then P else W := 6;
			  repeat
			    Inc(I)
			    W := I
			  until I > 3;
			  case V of
			    1: P;
			    2: P
			    else P
			  end;
			  while V > 0 do Dec(V);
			  P
			end;
			""");
	}

	public void testIncDecWithFix()
	{
		check("""
			type TRec = record Count: Integer; end;
			var I, J: Integer; Str: String; F: Single; Arr: array of Integer; R: TRec;
			function Idx: Integer; begin Result := 0; end;
			procedure OnTick;
			begin
			  <weak_warning descr="Can be replaced with 'Inc(I)'"><caret>I := I + 1</weak_warning>;
			  <weak_warning descr="Can be replaced with 'Inc(i)'">i := (1 + I)</weak_warning>;
			  <weak_warning descr="Can be replaced with 'Dec(J)'">J := J - 1</weak_warning>;
			  <weak_warning descr="Can be replaced with 'Inc(Arr[I])'">Arr[I] := Arr[I] + 1</weak_warning>;
			  <weak_warning descr="Can be replaced with 'Dec(R.Count)'">R.Count := R.Count - 1</weak_warning>;
			  I := I + 2;
			  I := 1 - I;
			  I := J + 1;
			  Str := Str + '1';
			  F := F + 1;
			  Arr[Idx] := Arr[Idx] + 1;
			  Inc(I);
			end;
			""");
		myFixture.launchAction(myFixture.findSingleIntention("Replace with 'Inc(I)'"));
		myFixture.checkResult("""
			type TRec = record Count: Integer; end;
			var I, J: Integer; Str: String; F: Single; Arr: array of Integer; R: TRec;
			function Idx: Integer; begin Result := 0; end;
			procedure OnTick;
			begin
			  Inc(I);
			  i := (1 + I);
			  J := J - 1;
			  Arr[I] := Arr[I] + 1;
			  R.Count := R.Count - 1;
			  I := I + 2;
			  I := 1 - I;
			  I := J + 1;
			  Str := Str + '1';
			  F := F + 1;
			  Arr[Idx] := Arr[Idx] + 1;
			  Inc(I);
			end;
			""");
	}

	// ---------------------------------------------------------------------------------------------------------------

	private void check(String text)
	{
		myFixture.configureByText("a.script", text);
		myFixture.checkHighlighting();
	}

}
