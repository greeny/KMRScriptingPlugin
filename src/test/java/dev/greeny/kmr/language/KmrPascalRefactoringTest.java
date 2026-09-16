package dev.greeny.kmr.language;

import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.refactoring.RefactoringActionHandler;
import com.intellij.refactoring.util.CommonRefactoringUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import dev.greeny.kmr.language.refactoring.KmrPascalExtractRoutineHandler;
import dev.greeny.kmr.language.refactoring.KmrPascalIntroduceConstantHandler;
import dev.greeny.kmr.language.refactoring.KmrPascalIntroduceVariableHandler;

/** Introduce Variable / Constant and Extract Method. In tests the handlers take the caret expression and all occurrences. */
public class KmrPascalRefactoringTest extends BasePlatformTestCase
{

	public void testIntroduceVariableWithOccurrencesAndExistingVarBlock()
	{
		introduceVariable("""
			var U: Integer;
			procedure OnTick;
			var Owner: Integer;
			begin
			  if States.UnitOwner(U) = 3 then
			    Owner := 3;
			  Owner := States.Unit<caret>Owner(U) + 1;
			end;
			""", """
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

	public void testIntroduceVariableCreatesVarBlockAndUsesDeclaredTypes()
	{
		introduceVariable("""
			procedure OnTick;
			begin
			  if States.UnitTy<caret>peEx(1) = utSerf then
			    Actions.ShowMsg(0, 'serf');
			end;
			""", """
			procedure OnTick;
			var
			  UnitTypeEx: TKMUnitType;
			begin
			  UnitTypeEx := States.UnitTypeEx(1);
			  if UnitTypeEx = utSerf then
			    Actions.ShowMsg(0, 'serf');
			end;
			""");
	}

	public void testIntroduceVariableWrapsAOneLinerBranch()
	{
		introduceVariable("""
			var A: Boolean;
			procedure OnTick;
			begin
			  if A then
			    Actions.ShowMsg(0, 'Time: ' + IntToStr(States.Game<caret>Time));
			end;
			""", """
			var A: Boolean;
			procedure OnTick;
			var
			  GameTime: Cardinal;
			begin
			  if A then
			  begin
			    GameTime := States.GameTime;
			    Actions.ShowMsg(0, 'Time: ' + IntToStr(GameTime));
			  end;
			end;
			""");
	}

	public void testIntroduceVariableFromSelectionAndInRepeatCondition()
	{
		introduceVariable("""
			var X: Integer;
			procedure OnTick;
			begin
			  repeat
			    X := X + 1;
			  until <selection>X * 2</selection> > 10;
			end;
			""", """
			var X: Integer;
			procedure OnTick;
			var
			  Value: Integer;
			begin
			  repeat
			    X := X + 1;
			    Value := X * 2;
			  until Value > 10;
			end;
			""");
	}

	public void testIntroduceConstant()
	{
		invoke(new KmrPascalIntroduceConstantHandler(), """
			var X: Integer;
			// tick handler
			procedure OnTick;
			begin
			  if X > 10 then
			    X := <caret>10;
			end;
			""", """
			var X: Integer;
			const
			  VALUE = 10;

			// tick handler
			procedure OnTick;
			begin
			  if X > VALUE then
			    X := VALUE;
			end;
			""");
		invoke(new KmrPascalIntroduceConstantHandler(), """
			const
			  MAX = 3;
			procedure OnTick;
			begin
			  Actions.ShowMsg(0, <selection>'Hello world'</selection>);
			end;
			""", """
			const
			  MAX = 3;
			  HELLO_WORLD = 'Hello world';
			procedure OnTick;
			begin
			  Actions.ShowMsg(0, HELLO_WORLD);
			end;
			""");
	}

	public void testExtractProcedureWithInputsOutputsAndMovedLocals()
	{
		invoke(new KmrPascalExtractRoutineHandler(), """
			var G: Integer;
			// tick handler
			procedure OnTick(aTick: Integer);
			var I, Count: Integer; Total: Integer;
			begin
			  Count := 0;
			  <selection>for I := 1 to aTick do
			    if States.UnitOwner(I) = G then
			      Inc(Count);
			  Total := Count * 2;</selection>
			  Actions.ShowMsg(0, IntToStr(Total) + IntToStr(Count));
			end;
			""", """
			var G: Integer;
			procedure Extracted(aTick: Integer; var Count: Integer; var Total: Integer);
			var
			  I: Integer;
			begin
			  for I := 1 to aTick do
			    if States.UnitOwner(I) = G then
			      Inc(Count);
			  Total := Count * 2;
			end;

			// tick handler
			procedure OnTick(aTick: Integer);
			var Count: Integer; Total: Integer;
			begin
			  Count := 0;
			  Extracted(aTick, Count, Total);
			  Actions.ShowMsg(0, IntToStr(Total) + IntToStr(Count));
			end;
			""");
	}

	public void testExtractFunctionFromExpression()
	{
		invoke(new KmrPascalExtractRoutineHandler(), """
			function Half(aValue: Integer): Integer;
			begin
			  Result := <selection>(aValue + 1) div 2</selection>;
			end;
			""", """
			function Extracted(aValue: Integer): Integer;
			begin
			  Result := (aValue + 1) div 2;
			end;

			function Half(aValue: Integer): Integer;
			begin
			  Result := Extracted(aValue);
			end;
			""");
	}

	public void testExtractRefusesExitAndOuterBreak()
	{
		String text = """
			procedure OnTick;
			var I: Integer;
			begin
			  for I := 1 to 3 do
			  begin
			    <selection>if I = 2 then
			      break;</selection>
			  end;
			end;
			""";
		myFixture.configureByText("a.script", text);
		try {
			new KmrPascalExtractRoutineHandler().invoke(getProject(), myFixture.getEditor(), myFixture.getFile(), DataContext.EMPTY_CONTEXT);
			fail("expected a refusal");
		} catch (CommonRefactoringUtil.RefactoringErrorHintException e) {
			assertEquals("Cannot extract: the selection contains 'break' or 'continue' of a loop outside it", e.getMessage());
		}
		myFixture.checkResult(text);
	}

	private void introduceVariable(String before, String after)
	{
		invoke(new KmrPascalIntroduceVariableHandler(), before, after);
	}

	private void invoke(RefactoringActionHandler handler, String before, String after)
	{
		myFixture.configureByText("a.script", before);
		handler.invoke(getProject(), myFixture.getEditor(), myFixture.getFile(), DataContext.EMPTY_CONTEXT);
		myFixture.checkResult(after);
	}

}
