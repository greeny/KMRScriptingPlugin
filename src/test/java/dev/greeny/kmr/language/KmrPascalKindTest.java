package dev.greeny.kmr.language;

import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.testFramework.utils.parameterInfo.MockCreateParameterInfoContext;
import com.intellij.testFramework.utils.parameterInfo.MockParameterInfoUIContext;
import com.intellij.psi.PsiElement;
import dev.greeny.kmr.language.editor.KmrPascalDocumentationProvider;
import dev.greeny.kmr.language.editor.KmrPascalParameterInfoHandler;
import dev.greeny.kmr.language.inspections.KmrPascalKindInspection;
import dev.greeny.kmr.language.inspections.KmrPascalTypeCheckInspection;
import dev.greeny.kmr.language.psi.*;
import dev.greeny.kmr.language.resolve.KmrPascalCallable;
import dev.greeny.kmr.language.stubs.KmrPascalEvent;
import dev.greeny.kmr.language.stubs.KmrPascalEvents;
import dev.greeny.kmr.language.types.KmrKind;
import dev.greeny.kmr.language.types.KmrKindInference;
import dev.greeny.kmr.language.types.KmrType;
import dev.greeny.kmr.language.types.KmrTypeProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Kinds (unit ID, hand index, X/Y, ...) from the stubs, kind inference for user code, the kind inspection. */
public class KmrPascalKindTest extends BasePlatformTestCase
{

	private static final String PROGRAM = """
		type
		  TRec = record Home: Integer; Name: String; end;
		const
		  SLOT_UNIT = 0; SLOT_HOUSE = 1;
		var
		  U, H, G, X, Y, Mixed, Plain, Cnt, PX, PY, GT, R, DT, I, J: Integer;
		  Slots: array of Integer;
		  // @kind unitId|houseId
		  Either: Integer;
		  Any: Integer;
		  Units: array of Integer;
		  Grid: array [0..3] of array of Integer;
		  Rec: TRec;
		  // @kind unitId
		  Tagged: Integer;
		  // @kind none
		  Free: Integer;
		  // @kind score
		  Score: Integer;
		  // @kind houseId
		  Houses: array of Integer;
		function FindUnit: Integer; begin Result := States.UnitAt(1, 1); end;
		function Twice(aValue: Integer): Integer; begin Twice := aValue * 2; end;
		procedure Kill(aUnit: Integer); begin Actions.UnitKill(aUnit, True); end;
		// @kind aH houseId
		procedure Take(aH: Integer); begin end;
		procedure Log(aValue: Integer); begin end;
		procedure OnHouseBuilt(aHouse: Integer); begin H := aHouse; end;
		{$EVENT evtUnitDied:MyDied}
		procedure MyDied(aU, aK: Integer); begin end;
		procedure OnTick;
		begin
		  U := States.UnitAt(1, 1);
		  G := States.UnitsGroup(U);
		  X := States.UnitPositionX(U);
		  Y := X;
		  Units[0] := U;
		  Grid[1][2] := States.HouseAt(1, 1);
		  Rec.Home := States.UnitHome(U);
		  Mixed := U;
		  Mixed := H;
		  Slots[SLOT_UNIT] := U;
		  Slots[SLOT_HOUSE] := H;
		  Slots[I] := G;
		  Any := Either;
		  Plain := 5;
		  Cnt := States.GameTime;
		  Free := U;
		  Kill(U);
		  Kill(FindUnit);
		  Log(U);
		  Log(H);
		  States.AIDefencePositionGet(0, 0, PX, PY, GT, R, DT);
		end;
		""";

	public void testKindsFromStubs()
	{
		assertKind("Integer (unit ID)", "States.UnitAt(1, 1)");
		assertKind("Integer (X coordinate)", "States.HousePosition(1).X");
		assertKind("Integer (hand index)", "States.UnitOwner(1)");
		assertKind("Integer (unit type id)", "States.UnitType(1)");
		assertKind("Integer (tick count)", "States.GameTime");
		assertKind("Integer (tick count)", "States.GameTime + 100");
		assertKind("Integer (tick count)", "States.GameTime + States.PeaceTime");
		assertKind("Integer (X coordinate)", "States.HousePositionX(1) + 1");
		assertKind("Integer (hand index)", "States.UnitOwner(1) + 1");
		assertKind("Integer", "States.HousePositionX(1) - States.HousePositionX(2)");
		assertKind("Integer", "States.HousePositionX(1) * States.HousePositionY(1)");
		assertKind("Integer", "States.UnitAt(1, 1) + 1");
		assertKind("Integer", "-1");
		assertKind("Integer", "-States.UnitAt(1, 1)");
		assertKind("Integer", "Integer(States.UnitAt(1, 1))");
		assertKind("array of Integer (unit ID)", "States.PlayerGetAllUnits(0)");
		assertKind("Integer (unit ID)", "States.PlayerGetAllUnits(0)[0]");
		assertKind("Boolean", "States.UnitAt(1, 1) = States.UnitAt(2, 2)");
		// the coarse type is unchanged
		assertEquals("Integer", typeOf("States.UnitAt(1, 1)").toString());
		assertTrue(KmrType.isAssignable(typeOf("States.UnitAt(1, 1)"), typeOf("States.HouseAt(1, 1)")));
	}

	public void testFlowInference()
	{
		assertKind("Integer (unit ID)", "U");
		assertKind("Integer (group ID)", "G");
		assertKind("Integer (X coordinate)", "X");
		assertKind("Integer (X coordinate)", "Y"); // assigned from X: the inference follows the value, the inspection reports it
		assertKind("Integer (house ID)", "H"); // from the event handler parameter
		assertKind("Integer (unit ID)", "Units[0]");
		assertKind("array of Integer (unit ID)", "Units");
		assertKind("Integer (house ID)", "Grid[1][2]");
		assertKind("Integer (house ID)", "Grid[1, 2]");
		assertKind("Integer", "Grid[0][1]"); // a constant index that is never written: unknown
		assertKind("Integer (house ID)", "Grid[I][J]"); // computed indexes see the common kind of all writes
		// per-index kinds: constant indexes are their own slots, computed indexes see the common kind or nothing
		assertKind("Integer (unit ID)", "Slots[SLOT_UNIT]");
		assertKind("Integer (unit ID)", "Slots[0]");
		assertKind("Integer (house ID)", "Slots[SLOT_HOUSE]");
		assertKind("Integer", "Slots[I]"); // mixed: unit ID at 0, house ID at 1
		assertKind("Integer", "Slots[I] + 1"); // arithmetic on a mixed value stays unknown
		assertKind("Integer (group ID)", "Slots[7]"); // never written statically: only the computed write Slots[I] := G can reach it
		assertFalse(KmrKindInference.resultOf(declaration("Slots"), 1).conflict);
		assertKind("Integer (unit ID or house ID)", "Either");
		assertKind("Integer (unit ID or house ID)", "Any");
		assertKind("Integer (house ID)", "Rec.Home"); // UnitHome returns the house the unit works in
		assertKind("Integer (unit ID)", "FindUnit");
		assertKind("Integer (unit ID)", "FindUnit()");
		assertKind("Integer (tick count)", "Cnt");
		assertKind("Integer", "Plain");
		assertKind("Integer", "Twice(1)"); // aValue * 2 has no kind
		// var/out arguments take the parameter's kind
		assertKind("Integer (X coordinate)", "PX");
		assertKind("Integer (Y coordinate)", "PY");
		assertKind("Integer (group type id)", "GT");
		assertKind("Integer", "R");
		// conflicts
		assertKind("Integer", "Mixed");
		KmrKindInference.Result mixed = KmrKindInference.resultOf(declaration("Mixed"), 0);
		assertTrue(mixed.conflict);
		assertEquals(List.of("unit ID", "house ID"), mixed.kinds().stream().map(k -> k.label).toList());
	}

	public void testExplicitTagsAndEvents()
	{
		assertKind("Integer (unit ID)", "Tagged");
		assertKind("Integer", "Free"); // @kind none: assigned a unit ID but inference is off
		assertFalse(KmrKindInference.resultOf(declaration("Free"), 0).conflict);
		assertKind("Integer (score)", "Score");
		assertEquals(KmrKind.Policy.LABEL, typeOf("Score").valueKind.policy);
		assertKind("Integer (house ID)", "Houses[0]"); // the tag on an array names the element kind
		assertEquals("unit ID", kindOfParameter("Kill", "aUnit").label); // from the calls
		assertEquals("house ID", kindOfParameter("Take", "aH").label); // @kind aH houseId on the routine
		assertEquals("house ID", kindOfParameter("OnHouseBuilt", "aHouse").label); // event by name
		assertEquals("unit ID", kindOfParameter("MyDied", "aU").label); // event by {$EVENT}
		assertEquals("hand index", kindOfParameter("MyDied", "aK").label);
		assertNull(kindOfParameter("Twice", "aValue"));
		KmrPascalParameterIdentifier logValue = parameter("Log", "aValue");
		assertNull(KmrKindInference.inferredKindOf(logValue, 0));
		assertTrue(KmrKindInference.resultOf(logValue, 0).conflict);
	}

	public void testPresentation()
	{
		myFixture.configureByText("a.script", "procedure P; begin Actions.ShowMsg(0, ''); end;");
		KmrPascalEvent unitDied = KmrPascalEvents.getInstance(getProject()).findByHandlerName("r16020", "OnUnitDied");
		assertNotNull(unitDied);
		assertEquals("(aUnit: Integer (unit ID); aKillerOwner: Integer (hand index))", unitDied.getSignature().getSignatureText());
		assertEquals("(aUnit: Integer; aKillerOwner: Integer)", unitDied.getSignature().getSignatureText(false));

		PsiFile file = myFixture.configureByText("a.script", "procedure P; begin Actions.Show<caret>Msg(0, ''); end;");
		PsiElement showMsg = file.findReferenceAt(myFixture.getCaretOffset()).resolve();
		KmrPascalCallable callable = KmrPascalCallable.of(showMsg);
		assertNotNull(callable);
		assertTrue(callable.getSignatureText(), callable.getSignatureText().startsWith("(aHand: ShortInt (hand index); aText: "));
		assertFalse(callable.getSignatureText().contains("TKM"));
		String doc = new KmrPascalDocumentationProvider().generateDoc(showMsg, null);
		assertTrue(doc, doc.contains("aHand: ShortInt (hand index)"));

		myFixture.configureByText("a.script", "procedure P; begin Actions.ShowMsg(<caret>0, ''); end;");
		KmrPascalParameterInfoHandler handler = new KmrPascalParameterInfoHandler();
		MockCreateParameterInfoContext create = new MockCreateParameterInfoContext(myFixture.getEditor(), myFixture.getFile());
		PsiElement call = handler.findElementForParameterInfo(create);
		assertNotNull(call);
		MockParameterInfoUIContext<PsiElement> ui = new MockParameterInfoUIContext<>(call);
		handler.updateUI((KmrPascalCallable) create.getItemsToShow()[0], ui);
		assertTrue(ui.getText(), ui.getText().startsWith("aHand: ShortInt (hand index), aText: "));
	}

	public void testKindInspection()
	{
		myFixture.enableInspections(new KmrPascalKindInspection(), new KmrPascalTypeCheckInspection());
		myFixture.configureByText("a.script", """
			const SLOT_UNIT = 0; SLOT_HOUSE = 1;
			var Mixed, Plain, Cnt, PX, PY, GT, R, DT, I: Integer; Slots: array of Integer;
			procedure Log(aValue: Integer); begin end;
			// @kind aId unitId|houseId
			procedure TakeEither(aId: Integer); begin end;
			procedure OnHouseBuilt(aHouse: Integer);
			var U, H, X, Y: Integer;
			begin
			  U := States.UnitAt(1, 1);
			  H := aHouse;
			  X := States.HousePositionX(H);
			  Y := States.HousePositionY(H);
			  Actions.UnitKill(U, True);
			  Actions.HouseDestroy(H, True);
			  Actions.UnitKill(<warning descr="Argument 'aUnitID' of 'UnitKill' expects a unit ID, got a house ID">H</warning>, True);
			  Actions.UnitKill(<warning descr="Integer literal passed as 'aUnitID' of 'UnitKill', a unit ID">5</warning>, True);
			  Actions.UnitKill(-1, True);
			  Actions.ShowMsg(0, 'ok');
			  Actions.ShowMsg(<warning descr="Argument 'aHand' of 'ShowMsg' expects a hand index, got a unit ID">U</warning>, 'x');
			  Actions.GiveUnitEx(0, utSerf, X, Y, dirN);
			  Actions.GiveUnitEx(0, utSerf, X + 1, Y - 1, dirN);
			  Actions.GiveUnitEx(0, utSerf, <warning descr="Argument 'X' of 'GiveUnitEx' expects an X coordinate, got a Y coordinate (swapped X/Y?)">Y</warning>, <warning descr="Argument 'Y' of 'GiveUnitEx' expects a Y coordinate, got an X coordinate (swapped X/Y?)">X</warning>, dirN);
			  Actions.GiveUnitEx(0, utSerf, 5, 6, dirN);
			  if U = -1 then Exit;
			  if U > 0 then Exit;
			  if U = States.UnitAt(2, 2) then Exit;
			  if <warning descr="Comparison between a unit ID and a house ID">U = H</warning> then Exit;
			  if <warning descr="Comparison between an X coordinate and a Y coordinate (swapped X/Y?)">X = Y</warning> then Exit;
			  if <warning descr="Ordering comparison between unit IDs (ids have no order)">U < States.UnitAt(2, 2)</warning> then Exit;
			  Plain := <warning descr="Arithmetic on a unit ID ('U')">U + 1</warning>;
			  <warning descr="'Inc' on 'U', a unit ID">Inc(U)</warning>;
			  Plain := X * Y;
			  Cnt := States.GameTime;
			  Cnt := Cnt + 1;
			  Cnt := Cnt mod 600;
			  if States.GameTime mod 600 = 0 then Exit;
			  aHouse := <warning descr="Cannot assign a unit ID to 'aHouse', a house ID">U</warning>;
			  aHouse := <warning descr="Integer literal assigned to 'aHouse', a house ID">7</warning>;
			  aHouse := -1;
			  Mixed := <warning descr="Conflicting kinds are assigned to 'Mixed': a unit ID here, a house ID elsewhere">U</warning>;
			  Mixed := <warning descr="Conflicting kinds are assigned to 'Mixed': a house ID here, a unit ID elsewhere">H</warning>;
			  Mixed := 3;
			  Slots[SLOT_UNIT] := <warning descr="Conflicting kinds are assigned to 'Slots': a unit ID here, a house ID elsewhere">U</warning>;
			  Slots[SLOT_HOUSE] := H;
			  Slots[I] := H;
			  Slots[SLOT_UNIT] := <warning descr="Conflicting kinds are assigned to 'Slots': a house ID here, a unit ID elsewhere">H</warning>;
			  Actions.UnitKill(Slots[SLOT_UNIT], True);
			  Actions.UnitKill(<warning descr="Argument 'aUnitID' of 'UnitKill' expects a unit ID, got a house ID">Slots[SLOT_HOUSE]</warning>, True);
			  Actions.UnitKill(Slots[I], True);
			  TakeEither(U); TakeEither(H);
			  TakeEither(<warning descr="Argument 'aId' of 'TakeEither' expects a unit ID or house ID, got a hand index">States.UnitOwner(U)</warning>);
			  Log(<warning descr="Conflicting kinds are passed as 'aValue' of 'Log': a unit ID here, a house ID elsewhere">U</warning>);
			  Log(<warning descr="Conflicting kinds are passed as 'aValue' of 'Log': a house ID here, a unit ID elsewhere">H</warning>);
			  States.AIDefencePositionGet(0, 0, PX, PY, GT, R, DT);
			  States.ClosestUnit(0, 1, 1, 0); // deprecated in favour of ClosestUnitEx: reported by the deprecation inspection instead
			  States.ClosestUnitEx(0, 1, 1, utSerf);
			  Plain := Integer(U) + 1;
			end;
			procedure OnBeacon(aPlayer, aX, aY: Integer);
			var Pos: Integer;
			begin
			  aX := <warning descr="Cannot assign a Y coordinate to 'aX', an X coordinate (swapped X/Y?)">aY</warning>;
			  States.AIDefencePositionGet(aPlayer, 0, <warning descr="'aY' (a Y coordinate) receives an X coordinate from 'aX' of 'AIDefencePositionGet' (swapped X/Y?)">aY</warning>, <warning descr="'aX' (an X coordinate) receives a Y coordinate from 'aY' of 'AIDefencePositionGet' (swapped X/Y?)">aX</warning>, GT, R, DT);
			  Pos := <warning descr="Conflicting kinds are assigned to 'Pos': an X coordinate here, a Y coordinate elsewhere">States.HousePositionX(States.HouseAt(1, 1))</warning>;
			  Pos := <warning descr="Conflicting kinds are assigned to 'Pos': a Y coordinate here, an X coordinate elsewhere">aY</warning>;
			  States.AIDefencePositionGet(aPlayer, 0, <warning descr="Conflicting kinds are assigned to 'Pos': an X coordinate here (from 'aX' of 'AIDefencePositionGet'), a Y coordinate elsewhere">Pos</warning>, PY, GT, R, DT);
			end;
			""");
		myFixture.checkHighlighting(true, false, true);
	}

	public void testArraysPassedWhole()
	{
		myFixture.enableInspections(new KmrPascalKindInspection());
		myFixture.configureByText("a.script", """
			const X__ = 0; Y__ = 1; TIME__ = 2; TARGETED_PLAYER_ = 4; ATTACKER_UNIT_ID_ = 7; ATTACKER_GROUP_ID_ = 8; TIME_WAS_RESET_ = 10;
			var attacks: array of array of array of Integer;
			procedure Record_Attack(aUnit: Integer; groups: array of Integer);
			var attack: array of Integer; i: Integer;
			begin
			  SetLength(attack, 11);
			  attack[X__] := States.UnitPositionX(aUnit);
			  attack[Y__] := States.UnitPositionY(aUnit);
			  attack[ATTACKER_UNIT_ID_] := aUnit;
			  attack[ATTACKER_GROUP_ID_] := States.UnitsGroup(aUnit);
			  attack[TARGETED_PLAYER_] := States.UnitOwner(aUnit);
			  for i := 0 to High(groups) do attack[i + 11] := groups[i];
			  attack[TIME__] := States.GameTime;
			  SetLength(attacks[attack[TARGETED_PLAYER_]], Length(attacks[attack[TARGETED_PLAYER_]]) + 1);
			  attacks[attack[TARGETED_PLAYER_]][High(attacks[attack[TARGETED_PLAYER_]])] := attack;
			end;
			function Moved(player: Integer; attack: array of Integer; group: Integer): Boolean;
			begin
			  Result := States.UnitDead(attack[ATTACKER_UNIT_ID_]);
			  Actions.GroupOrderHalt(States.UnitsGroup(States.GroupMember(attack[group], 0)));
			  Actions.GroupOrderHalt(attack[ATTACKER_GROUP_ID_]);
			  Actions.HouseDestroy(States.ClosestHouse(attack[TARGETED_PLAYER_], attack[X__], attack[Y__], -1), True);
			end;
			procedure Check(player, attack, group: Integer);
			begin
			  if Moved(player, attacks[player][attack], group) then
			    attacks[player][attack][TIME__] := States.GameTime + 10;
			  attacks[player][attack][TIME_WAS_RESET_] := 1;
			  attacks[player][attack][group] := -1;
			  Actions.GroupOrderHalt(attacks[player][attack][group]);
			  Actions.UnitKill(attacks[player][attack][ATTACKER_UNIT_ID_], True);
			  Actions.GroupOrderHalt(attacks[player][attack][ATTACKER_GROUP_ID_]);
			  Actions.UnitKill(<warning descr="Argument 'aUnitID' of 'UnitKill' expects a unit ID, got a group ID">attacks[player][attack][ATTACKER_GROUP_ID_]</warning>, True);
			  Actions.UnitKill(<warning descr="Argument 'aUnitID' of 'UnitKill' expects a unit ID, got a tick count">attacks[player][attack][TIME__]</warning>, True);
			end;
			""");
		myFixture.checkHighlighting(true, false, true);
	}

	public void testKindAnnotationOnExpressions()
	{
		myFixture.enableInspections(new KmrPascalKindInspection());
		myFixture.configureByText("a.script", """
			var Stash: array of Integer;
			procedure P(aAny: Integer);
			var H, U, M, N: Integer;
			begin
			  Stash[0] := States.UnitAt(1, 1);
			  Stash[1] := States.HouseAt(1, 1);
			  // the element read through a computed index is unknown: nothing is reported
			  Actions.UnitKill(Stash[aAny], True);
			  {@kind houseId}
			  H := Stash[aAny];
			  Actions.HouseDestroy(H, True);
			  Actions.UnitKill(<warning descr="Argument 'aUnitID' of 'UnitKill' expects a unit ID, got a house ID">H</warning>, True);
			  // @kind unitId
			  U := Stash[aAny];
			  Actions.UnitKill(U, True);
			  Actions.HouseDestroy({@kind houseId} Stash[aAny], True);
			  Actions.UnitKill({@kind houseId} <warning descr="Argument 'aUnitID' of 'UnitKill' expects a unit ID, got a house ID">Stash[aAny]</warning>, True);
			  {@kind none}
			  H := U;
			  M := H;
			  {@kind none}
			  M := U;
			  Actions.HouseDestroy(M, True);
			  // a blank line in between: the comment does not annotate the assignment
			  // @kind houseId

			  N := U;
			  Actions.HouseDestroy(<warning descr="Argument 'aHouseID' of 'HouseDestroy' expects a house ID, got a unit ID">N</warning>, True);
			end;
			""");
		myFixture.checkHighlighting(true, false, true);
	}

	public void testNoFalsePositivesOnSampleScript() throws Exception
	{
		myFixture.enableInspections(new KmrPascalKindInspection());
		myFixture.configureByText("Sample.script", Files.readString(Path.of("src/test/testData/parser/Sample.script")));
		myFixture.checkHighlighting(true, false, true);
	}

	// ---------------------------------------------------------------------------------------------------------------

	private void assertKind(String expected, String expression)
	{
		KmrType type = typeOf(expression);
		assertEquals(expression + " -> " + type.describe(), expected, type.describe());
	}

	private KmrType typeOf(String expression)
	{
		PsiFile file = myFixture.configureByText("a.script", PROGRAM + "procedure T; begin Dummy := " + expression + "; end;");
		KmrPascalAssignment assignment = PsiTreeUtil.findChildrenOfType(file, KmrPascalAssignment.class).stream().reduce((a, b) -> b).orElseThrow();
		KmrPascalExpression right = assignment.getExpressionList().get(1);
		return withKinds(KmrTypeProvider.typeOf(right), right, 0);
	}

	/** The coarse type plus the kinds (declared or inferred) at every array level, as the inspection sees them. */
	private static KmrType withKinds(KmrType type, KmrPascalExpression expression, int levels)
	{
		if (type.is(KmrType.Kind.ARRAY) && type.element != null) {
			return KmrType.arrayOf(withKinds(type.element, expression, levels + 1));
		}
		return type.is(KmrType.Kind.INTEGER) ? type.withValueKind(KmrKindInference.kindOf(expression, levels)) : type;
	}

	private PsiElement declaration(String name)
	{
		PsiFile file = myFixture.configureByText("a.script", PROGRAM);
		return PsiTreeUtil.findChildrenOfType(file, KmrPascalVarIdentifier.class).stream().filter(v -> name.equals(v.getName())).findFirst().orElseThrow();
	}

	private KmrPascalParameterIdentifier parameter(String routine, String name)
	{
		PsiFile file = myFixture.configureByText("a.script", PROGRAM);
		KmrPascalRoutineDeclaration declaration = PsiTreeUtil.findChildrenOfType(file, KmrPascalRoutineDeclaration.class).stream()
			.filter(r -> routine.equals(r.getName())).findFirst().orElseThrow();
		return KmrPascalCallable.of(declaration).getParameterIdentifiers().stream().filter(p -> name.equals(p.getName())).findFirst().orElseThrow();
	}

	private KmrKind kindOfParameter(String routine, String name)
	{
		return KmrKindInference.kindOfDeclaration(parameter(routine, name), 0);
	}

}
