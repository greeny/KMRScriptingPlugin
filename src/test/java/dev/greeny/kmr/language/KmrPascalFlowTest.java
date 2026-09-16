package dev.greeny.kmr.language;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import dev.greeny.kmr.language.editor.KmrPascalDocumentationProvider;
import dev.greeny.kmr.language.inspections.KmrPascalConstantConditionInspection;
import dev.greeny.kmr.language.inspections.KmrPascalRepeatedQueryInspection;

/** The flow analysis: known values in conditions, repeated States queries, values on hover. */
public class KmrPascalFlowTest extends BasePlatformTestCase
{

	@Override
	protected void setUp() throws Exception
	{
		super.setUp();
		myFixture.enableInspections(new KmrPascalConstantConditionInspection(), new KmrPascalRepeatedQueryInspection());
	}

	public void testNestedConditionsOnABoolean()
	{
		check("""
			var A: Boolean;
			procedure OnTick;
			begin
			  if A = True then
			  begin
			    if <warning descr="Condition 'A = False' is always false (A is True here)">A = False</warning> then
			      Actions.ShowMsg(0, 'never');
			    if <warning descr="Condition 'A' is always true (A is True here)">A</warning> then
			      Actions.ShowMsg(0, 'always');
			  end
			  else if <warning descr="Condition 'A = False' is always true (A is False here)">A = False</warning> then
			    Actions.ShowMsg(0, 'else');
			  if A = False then
			    Actions.ShowMsg(0, 'unknown again after the if');
			end;
			""");
	}

	public void testExitAndAssignmentsAreFollowed()
	{
		check("""
			var Ok: Boolean; Count: Integer;
			procedure OnTick;
			var Found: Boolean;
			begin
			  if not Ok then
			    exit;
			  if <warning descr="Condition 'Ok' is always true (Ok is True here)">Ok</warning> then
			    Count := 1;
			  Found := False;
			  if <warning descr="Condition 'Found' is always false (Found is False here)">Found</warning> then
			    Count := 2;
			  Count := 5;
			  if <warning descr="Condition 'Count > 3' is always true (Count is 5 here)">Count > 3</warning> then
			    Found := True;
			  Count := States.GameTime;
			  if Count > 3 then
			    Found := True;
			end;
			""");
	}

	public void testRangesAndCaseLabels()
	{
		check("""
			var X: Integer;
			procedure OnTick;
			begin
			  if X > 5 then
			  begin
			    if <warning descr="Condition 'X < 3' is always false (X is >= 6 here)">X < 3</warning> then
			      X := 0;
			    if X > 10 then
			      X := 0;
			  end;
			  X := States.UnitAt(1, 1);
			  case X of
			    1: if <warning descr="Condition 'X = 1' is always true (X is 1 here)">X = 1</warning> then X := 2;
			    2..4: if <warning descr="Condition 'X = 7' is always false (X is 2..4 here)">X = 7</warning> then X := 2;
			    else if <warning descr="Condition 'X = 1' is always false (X is not 1 here)">X = 1</warning> then X := 2;
			  end;
			end;
			""");
	}

	public void testOperandsOfAndOrAreCheckedSeparately()
	{
		check("""
			var A, B: Integer;
			procedure OnTick;
			begin
			  if (A = 1) and <warning descr="Condition '(A = 2)' is always false (A is 1 here)">(A = 2)</warning> then
			    B := 1;
			  if (A = 1) or <warning descr="Condition '(A <> 1)' is always true (A is not 1 here)">(A <> 1)</warning> then
			    B := 1;
			  B := 5;
			  if (A > 0) and <warning descr="Condition '(B = 1)' is always false (B is 5 here)">(B = 1)</warning> then
			    B := 2;
			  if (A > 0) or <warning descr="Condition '(B > 4)' is always true (B is 5 here)">(B > 4)</warning> then
			    B := 2;
			  if (A > 0) and (B = A) then
			    B := 2;
			end;
			""");
	}

	public void testConstantsByThemselvesAndLoopsAreNotReported()
	{
		check("""
			const DEBUG = False;
			var I, N: Integer; Done: Boolean;
			procedure OnTick;
			begin
			  if DEBUG then
			    N := 1;
			  while True do
			  begin
			    N := 2;
			    break;
			  end;
			  N := 0;
			  for I := 1 to 10 do
			    if N = 0 then
			      N := I;
			  if N = 0 then
			    N := 1;
			  Done := False;
			  repeat
			    if Done then
			      N := 3;
			    Done := States.PlayerIsAI(0);
			  until Done;
			  if <warning descr="Condition 'Done' is always true (Done is True here)">Done</warning> then
			    N := 4;
			end;
			""");
	}

	public void testFunctionResultAndScriptRoutineCalls()
	{
		check("""
			var G: Integer;
			procedure Other; begin G := 5; end;
			function Check: Boolean;
			var L: Integer;
			begin
			  Result := False;
			  if G = 1 then
			  begin
			    Result := True;
			    exit;
			  end;
			  if <warning descr="Condition 'Result' is always false (Result is False here)">Result</warning> then
			    G := 2;
			  G := 3; L := 3;
			  Other;
			  if G = 3 then
			    G := 4;
			  if <warning descr="Condition 'L = 3' is always true (L is 3 here)">L = 3</warning> then
			    G := 4;
			end;
			""");
	}

	public void testStatesQueriesAreStableUntilActions()
	{
		check("""
			var U: Integer;
			procedure OnTick;
			var Owner: Integer;
			begin
			  if States.UnitOwner(U) = 3 then
			  begin
			    if <warning descr="Condition 'States.UnitOwner(U) = 3' is always true (States.UnitOwner(U) is 3 here)"><weak_warning descr="'States.UnitOwner(U)' was already evaluated on line 5 and returns the same value here (no Actions call in between)">States.UnitOwner(U)</weak_warning> = 3</warning> then
			      Owner := 3;
			    Actions.UnitKill(U, False);
			    if States.UnitOwner(U) = 3 then
			      Owner := 3;
			  end;
			  Owner := <weak_warning descr="'States.UnitOwner(U)' was already evaluated on line 5 and returns the same value here (no Actions call in between)">States.UnitOwner(U)</weak_warning>;
			  U := States.UnitAt(1, 1);
			  Owner := States.UnitOwner(U);
			  Actions.ShowMsg(0, 'presentation only');
			  Owner := <weak_warning descr="'States.UnitOwner(U)' was already evaluated on line 15 and returns the same value here (no Actions call in between)">States.UnitOwner(U)</weak_warning>;
			  Actions.ShowMsg(0, IntToStr(States.UnitPositionX(States.GroupMember(U, 0))));
			  Actions.ShowMsg(0, IntToStr(<weak_warning descr="'States.UnitPositionX(States.GroupMember(U, 0))' was already evaluated on line 18 and returns the same value here (no Actions call in between)">States.UnitPositionX(States.GroupMember(U, 0))</weak_warning>));
			  if States.KaMRandomI(3) = 1 then
			    if States.KaMRandomI(3) = 1 then
			      Owner := 1;
			  if Utils.AbsI(Owner) = 1 then
			    if <warning descr="Condition 'Utils.AbsI(Owner) = 1' is always true (Utils.AbsI(Owner) is 1 here)">Utils.AbsI(Owner) = 1</warning> then
			      Owner := 1;
			end;
			""");
	}

	public void testRepeatedQueryIsExtractedToAVariable()
	{
		myFixture.configureByText("a.script", """
			var U: Integer;
			procedure OnTick;
			var Owner: Integer;
			begin
			  if States.UnitOwner(U) = 3 then
			    Owner := 3;
			  Owner := States.Unit<caret>Owner(U) + 1;
			end;
			""");
		myFixture.launchAction(myFixture.findSingleIntention("Extract to variable"));
		myFixture.checkResult("""
			var U: Integer;
			procedure OnTick;
			var Owner: Integer; UnitOwner: Integer;
			begin
			  UnitOwner := States.UnitOwner(U);
			  if UnitOwner = 3 then
			    Owner := 3;
			  Owner := UnitOwner + 1;
			end;
			""");
	}

	public void testKnownValueOnHover()
	{
		KmrPascalDocumentationProvider provider = new KmrPascalDocumentationProvider();
		myFixture.configureByText("a.script", """
			var A: Boolean; U: Integer;
			procedure OnTick;
			begin
			  if A then
			    if States.UnitOwner(U) = 3 then
			      Actions.ShowMsg(0, IntToStr(States.Unit<caret>Owner(U)));
			end;
			""");
		PsiElement original = myFixture.getFile().findElementAt(myFixture.getCaretOffset());
		PsiReference reference = myFixture.getFile().findReferenceAt(myFixture.getCaretOffset());
		assertNotNull(reference);
		String doc = provider.generateDoc(reference.resolve(), original);
		assertTrue(doc, doc.contains("Value here") && doc.contains("3 (from the condition on line 5)"));
		String hover = provider.getQuickNavigateInfo(reference.resolve(), original);
		assertTrue(hover, hover.contains(" = 3 "));

		myFixture.configureByText("b.script", """
			var A: Boolean;
			procedure OnTick;
			begin
			  if A then
			    A := not <caret>A;
			end;
			""");
		original = myFixture.getFile().findElementAt(myFixture.getCaretOffset());
		reference = myFixture.getFile().findReferenceAt(myFixture.getCaretOffset());
		assertNotNull(reference);
		doc = provider.generateDoc(reference.resolve(), original);
		assertTrue(doc, doc.contains("True (from the condition on line 4)"));
		// nothing known: no section
		myFixture.configureByText("c.script", "var A: Boolean;\nprocedure OnTick;\nbegin\n  A := not <caret>A;\nend;\n");
		original = myFixture.getFile().findElementAt(myFixture.getCaretOffset());
		reference = myFixture.getFile().findReferenceAt(myFixture.getCaretOffset());
		doc = provider.generateDoc(reference.resolve(), original);
		assertFalse(doc, doc.contains("Value here"));
	}

	private void check(String text)
	{
		myFixture.configureByText("a.script", text);
		myFixture.checkHighlighting();
	}

}
