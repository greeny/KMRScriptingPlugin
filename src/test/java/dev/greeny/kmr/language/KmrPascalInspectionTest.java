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

	public void testDeprecatedCallIsRewrittenToExVariant()
	{
		// numbers and named constants become enum members, unchanged parameters keep their text and formatting
		assertFixed("Replace with 'GiveUnitEx'", """
			const UT_SERF = 0; DIR_DOWN = 4;
			procedure OnTick;
			var U: Integer;
			begin
			  U := Actions.Give<caret>Unit(0, UT_SERF,
			    10, 20, DIR_DOWN);
			  Actions.GiveWares(0, 1, 5);
			end;
			""", """
			const UT_SERF = 0; DIR_DOWN = 4;
			procedure OnTick;
			var U: Integer;
			begin
			  U := Actions.GiveUnitEx(0, utSerf,
			    10, 20, dirS);
			  Actions.GiveWares(0, 1, 5);
			end;
			""");
		assertFixed("Replace with 'MarketSetTradeEx'", "procedure OnTick; begin Actions.Market<caret>SetTrade(1, (27), 8, 1); end;",
			"procedure OnTick; begin Actions.MarketSetTradeEx(1, wtFish, wtWine, 1); end;");
		// -1 is "any" for the Closest functions
		assertFixed("Replace with 'ClosestUnitEx'", "procedure OnTick; begin States.Closest<caret>Unit(0, 1, 1, -1); end;",
			"procedure OnTick; begin States.ClosestUnitEx(0, 1, 1, utAny); end;");
		// a TByteSet becomes a set of the enum
		assertFixed("Replace with 'ClosestGroupMultipleTypesEx'", "procedure OnTick; begin States.Closest<caret>GroupMultipleTypes(0, 1, 1, [0, 2]); end;",
			"procedure OnTick; begin States.ClosestGroupMultipleTypesEx(0, 1, 1, [gtMelee, gtRanged]); end;");
		// the Ex variants with a different shape whose old behaviour is expressible
		assertFixed("Replace with 'HouseAddBuildingProgressEx'", "procedure OnTick; begin Actions.HouseAdd<caret>BuildingProgress(States.HouseAt(1, 1)); end;",
			"procedure OnTick; begin Actions.HouseAddBuildingProgressEx(States.HouseAt(1, 1), 1); end;");
		assertFixed("Replace with 'GiveHouseSiteEx'", "procedure OnTick; begin Actions.Give<caret>HouseSite(0, 11, 1, 1, False); end;",
			"procedure OnTick; begin Actions.GiveHouseSiteEx(0, htStore, 1, 1, 0, 0); end;");
	}

	public void testDeprecatedResultIsRewrittenWithItsConstants()
	{
		assertFixed("Replace with 'HouseTypeEx'", """
			procedure OnTick;
			var H: Integer;
			begin
			  if 11 = States.House<caret>Type(H) then
			    H := 0;
			end;
			""", """
			procedure OnTick;
			var H: Integer;
			begin
			  if htStore = States.HouseTypeEx(H) then
			    H := 0;
			end;
			""");
		assertFixed("Replace with 'UnitTypeEx'", """
			procedure OnTick;
			var U: Integer;
			begin
			  case States.Unit<caret>Type(U) of
			    0, 13: U := 1;
			    -1: U := 2;
			    else U := 3;
			  end;
			end;
			""", """
			procedure OnTick;
			var U: Integer;
			begin
			  case States.UnitTypeEx(U) of
			    utSerf, utRecruit: U := 1;
			    utNone: U := 2;
			    else U := 3;
			  end;
			end;
			""");
		// a legacy result feeding a legacy call: the fix on either rewrites both
		assertFixed("Replace with 'GiveUnitEx'", "procedure OnTick; var U: Integer; begin U := Actions.GiveUnit(0, States.Unit<caret>Type(U), 1, 1, 0); end;",
			"procedure OnTick; var U: Integer; begin U := Actions.GiveUnitEx(0, States.UnitTypeEx(U), 1, 1, dirN); end;");
	}

	public void testDeprecatedCallWithoutExactMigrationHasNoFix()
	{
		assertNoFix("Replace with 'GiveUnitEx'", "procedure OnTick; var T: Integer; begin Actions.Give<caret>Unit(0, T, 1, 1, 0); end;");
		assertNoFix("Replace with 'GiveUnitEx'", "procedure OnTick; begin Actions.Give<caret>Unit(0, 28, 1, 1, 0); end;");
		assertNoFix("Replace with 'GiveUnitEx'", "procedure OnTick; begin Actions.Give<caret>Unit(0, -1, 1, 1, 0); end;");
		assertNoFix("Replace with 'GiveHouseSiteEx'", "procedure OnTick; begin Actions.Give<caret>HouseSite(0, 11, 1, 1, True); end;");
		assertNoFix("Replace with 'HouseAddBuildingMaterialsEx'", "procedure OnTick; begin Actions.HouseAdd<caret>BuildingMaterials(1); end;");
		assertNoFix("Replace with 'AIDefencePositionAddEx'", "procedure OnTick; begin Actions.AIDefence<caret>PositionAdd(0, 1, 1, 0, 0, 5, 0); end;");
		assertNoFix("Replace with 'UnitTypeEx'", "procedure OnTick; var U: Integer; begin if States.Unit<caret>Type(U) > 14 then U := 0; end;");
		assertNoFix("Replace with 'UnitTypeEx'", "procedure OnTick; var U: Integer; begin U := States.Unit<caret>Type(U); end;");
		assertNoFix("Replace with 'UnitTypeEx'", "procedure OnTick; var U: Integer; begin case States.Unit<caret>Type(U) of 0..13: U := 1; end; end;");
	}

	private void assertFixed(String fixName, String before, String after)
	{
		myFixture.configureByText("a.script", before);
		myFixture.launchAction(myFixture.findSingleIntention(fixName));
		myFixture.checkResult(after);
	}

	private void assertNoFix(String fixName, String text)
	{
		myFixture.configureByText("a.script", text);
		assertEmpty(myFixture.filterAvailableIntentions(fixName));
	}

	public void testDeprecatedEventHandlersAreReported()
	{
		check("""
			procedure <weak_warning descr="'OnUnitAfterDied' is deprecated: Use OnUnitAfterDiedEx instead">OnUnitAfterDied</weak_warning>(aUnitType: Integer; aOwner: Integer; aX, aY: Integer);
			begin
			  if (aUnitType = 14) and (aX = aY) then Actions.ShowMsg(aOwner, 'x');
			end;
			procedure MyTrade(aMarket: Integer; aWareFrom: Integer; aWareTo: Integer);
			begin
			  Actions.ShowMsg(0, IntToStr(aMarket + aWareFrom + aWareTo));
			end;
			{$EVENT <weak_warning descr="'evtMarketTrade' is deprecated: Use evtMarketTradeEx instead">evtMarketTrade</weak_warning>:MyTrade}
			procedure OnUnitAfterDiedEx(aUnitType: TKMUnitType; aOwner: Integer; aX, aY: Integer);
			begin
			  if (aUnitType = utSerf) and (aX = aY) then Actions.ShowMsg(aOwner, 'x');
			end;
			""");
	}

	public void testDeprecatedEventHandlerIsRewrittenToExVariant()
	{
		assertFixed("Replace with 'OnUnitAfterDiedEx'", """
			const UT_MILITIA = 14;
			procedure On<caret>UnitAfterDied(aUnitType: Integer; aOwner, aX, aY: Integer);
			begin
			  if (aUnitType = UT_MILITIA) or (aUnitType <> -1) then
			    Actions.GiveUnit(aOwner, aUnitType, aX, aY, 0);
			  if aUnitType in [0, 1] then
			    case aUnitType of
			      0: Actions.ShowMsg(aOwner, 'serf');
			      1, 2: Actions.ShowMsg(aOwner, 'other');
			    end;
			end;
			""", """
			const UT_MILITIA = 14;
			procedure OnUnitAfterDiedEx(aUnitType: TKMUnitType; aOwner, aX, aY: Integer);
			begin
			  if (aUnitType = utMilitia) or (aUnitType <> utNone) then
			    Actions.GiveUnitEx(aOwner, aUnitType, aX, aY, dirN);
			  if aUnitType in [utSerf, utWoodcutter] then
			    case aUnitType of
			      utSerf: Actions.ShowMsg(aOwner, 'serf');
			      utWoodcutter, utMiner: Actions.ShowMsg(aOwner, 'other');
			    end;
			end;
			""");
		// registered by directive: the directive moves to the Ex event, the handler keeps its name; groups are split
		assertFixed("Replace with 'OnHousePlanPlacedEx'", """
			{$EVENT evtHouse<caret>PlanPlaced:MyPlan}
			procedure MyPlan(aPlayer, aX, aY, aHouseType: Integer);
			begin
			  if aHouseType = 11 then Actions.ShowMsg(aPlayer, 'store');
			end;
			""", """
			{$EVENT evtHousePlanPlacedEx:MyPlan}
			procedure MyPlan(aPlayer, aX, aY: Integer; aHouseType: TKMHouseType);
			begin
			  if aHouseType = htStore then Actions.ShowMsg(aPlayer, 'store');
			end;
			""");
		// two enum parameters of one event
		assertFixed("Replace with 'OnMarketTradeEx'", """
			procedure On<caret>MarketTrade(aMarket, aWareFrom, aWareTo: Integer);
			begin
			  if (aWareFrom = 27) and (aWareTo <> 8) then Actions.MarketSetTrade(aMarket, aWareTo, aWareFrom, 1);
			end;
			""", """
			procedure OnMarketTradeEx(aMarket: Integer; aWareFrom, aWareTo: TKMWareType);
			begin
			  if (aWareFrom = wtFish) and (aWareTo <> wtWine) then Actions.MarketSetTradeEx(aMarket, aWareTo, aWareFrom, 1);
			end;
			""");
	}

	public void testDeprecatedEventHandlerWithoutExactMigrationHasNoFix()
	{
		assertNoFix("Replace with 'OnUnitAfterDiedEx'", """
			procedure On<caret>UnitAfterDied(aUnitType: Integer; aOwner, aX, aY: Integer);
			begin
			  Actions.ShowMsg(aOwner, IntToStr(aUnitType));
			end;
			""");
		assertNoFix("Replace with 'OnUnitAfterDiedEx'", """
			var Last: Integer;
			procedure On<caret>UnitAfterDied(aUnitType: Integer; aOwner, aX, aY: Integer);
			begin
			  Last := aUnitType;
			end;
			""");
		assertNoFix("Replace with 'OnUnitAfterDiedEx'", """
			procedure On<caret>UnitAfterDied(aUnitType: Integer; aOwner, aX, aY: Integer);
			begin
			  if aUnitType > 13 then Actions.ShowMsg(aOwner, 'warrior');
			end;
			""");
	}

	public void testDeprecatedAndArgumentCount()
	{
		check("""
			// @deprecated Use Newer
			procedure Older(<weak_warning descr="Unused parameter 'a'">a</weak_warning>: Integer); begin end;
			procedure Newer(<weak_warning descr="Unused parameter 'a'">a</weak_warning>: Integer); begin end;
			procedure OnTick;
			begin
			  <weak_warning descr="'Older' is deprecated: Use Newer">Older</weak_warning>(1);
			  Actions.<weak_warning descr="'GiveUnit' is deprecated: Use GiveUnitEx instead">GiveUnit</weak_warning>(0, 1, 1, 1, 0);
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

	public void testEventHandlerRegisteredMoreThanOnce()
	{
		check("""
			procedure MyBuilt(aHouse: Integer); begin end;
			procedure OnTick; begin end;
			{$EVENT evtHouseBuilt:MyBuilt}
			{$EVENT evtHouseBuilt:<error descr="Event 'evtHouseBuilt' already has the handler 'MyBuilt'">MyBuilt</error>}
			{$EVENT evtHousePlanDigged:<warning descr="Procedure 'MyBuilt' is already the handler of 'evtHouseBuilt'">MyBuilt</warning>}
			{$EVENT evtTick:<error descr="'OnTick' is the default handler of 'evtTick' and is registered by the game itself">OnTick</error>}
			""");
	}

	public void testMissingEventHandlerIsCreated()
	{
		assertFixed("Create procedure 'MyBuilt'", """
			{$EVENT evtHouseBuilt:My<caret>Built}
			""", """
			{$EVENT evtHouseBuilt:MyBuilt}

			procedure MyBuilt(aHouse: Integer);
			begin
			end;
			""");
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
			{$EVENT evtTick:MyTick}
			procedure OnTick; begin end;
			procedure MyTick; begin end;
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
