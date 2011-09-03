import java.util.*;
import java.io.*;

public class Fivetran implements MakoConstants {
	
	public static final int PRINT_SUBROUTINE = 41;
	public static final int INPUT_SUBROUTINE = 77;

	List<Formula>         program   = new ArrayList<Formula>();
	Map<Integer, Formula> formulas  = new HashMap<Integer, Formula>();
	Map<String, Variable> variables = new HashMap<String, Variable>();

	public static void main(String[] args) {
		Fivetran compiler = new Fivetran();
		compiler.parseFile(args[0]);
		MakoRom rom = compiler.compile();
		if (Arrays.asList(args).contains("--run")) {
			Mako.exec(rom.toArray());
		}
	}

	private void parseFile(String filename) {
		try {
			Scanner in = new Scanner(new File(filename));
			while(in.hasNextLine()) {
				String ln = in.nextLine().trim();
				
				// everything after a 'C' is a comment.
				if (ln.indexOf('C') >= 0) {
					ln = ln.substring(0, ln.indexOf('C'));
				}

				// ignore blank lines
				if (ln.length() < 1) { continue; }		

				// handle formula numbers:
				Cursor cursor = new Cursor(ln);
				Integer formulaNumber = null;
				if (cursor.isDigit()) {
					formulaNumber = cursor.parseNumber();
				}

				// parse the line:
				Formula line = parseFormula(cursor.line);
				if (formulaNumber != null) {
					line.number = formulaNumber;
					formulas.put(line.number, line);
				}
				program.add(line);
			}
			in.close();
			program.add(new StopFormula(""));
		}
		catch(IOException e) {
			throw new Error("Cannot open file '"+filename+"'");
		}
	}

	private Formula	parseFormula(String line) {
		if (line.length() == 0)           { return new NopFormula(); }
		if (line.startsWith("do"))        { return new DoFormula(       line.substring(2).trim()); }
		if (line.startsWith("if"))        { return new IfFormula(       line.substring(2).trim()); }
		if (line.startsWith("go to"))     { return new GoToFormula(     line.substring(5).trim()); }
		if (line.startsWith("stop"))      { return new StopFormula(     line.substring(4).trim()); }
		if (line.startsWith("read"))      { return new ReadFormula(     line.substring(4).trim()); }
		if (line.startsWith("print"))     { return new PrintFormula(    line.substring(5).trim()); }
		if (line.startsWith("dimension")) { return new DimensionFormula(line.substring(9).trim()); }
		return new AssignFormula(line);
	}

	private MakoRom compile() {
		// load the 'base' rom with various mandatory
		// library functions and memory regions.
		MakoRom rom = new MakoRom("BaseRom.bin");

		// lay down all referenced variables.
		for(Variable v : variables.values()) {
			v.emitStorage(rom);
		}

		// fix the program entrypoint
		rom.set(PC, rom.size(), MakoRom.Type.Array);
		rom.label("main", rom.size());

		// perform a first pass to determine the size
		// and effective address of each code segment.
		MakoRom romcopy = rom.copy();
		for(Formula f : program) {
			f.address = romcopy.size();
			f.emit(romcopy);
			if (f.gotoAfter != null) {
				romcopy.add(OP_JUMP, MakoRom.Type.Code);
				romcopy.add(f.gotoAfter, MakoRom.Type.Code);
			}
		}

		// recompile knowing these addresses.
		// inefficient, but simple!
		for(Formula f : program) {
			if (f.number != null) { rom.label("line "+f.number, rom.size()); }
			f.emit(rom);
			if (f.gotoAfter != null) {
				rom.add(OP_JUMP, MakoRom.Type.Code);
				rom.add(f.gotoAfter, MakoRom.Type.Code);
			}
		}

		rom.disassemble(System.out);
		return rom;
	}

	class AssignFormula extends Formula {
		Variable v;
		Expression a;

		AssignFormula(String line) {
			// variable = expression
			String[] parts = line.split("=");
			if (parts.length != 2) { fail(); }
			v = new Variable(  parts[0].trim(), variables);
			a = new Expression(parts[1].trim(), variables);
		}
	
		void emit(MakoRom rom) {
			a.emit(rom);
			v.emit(rom);
			rom.add(OP_STOR, MakoRom.Type.Code);
		}
	}
	
	class DoFormula extends Formula {
		Integer lastLine;
		Variable var;
		Expression first;
		Expression last;
		Expression step;

		DoFormula(String line) {
			// do lastLine varname = first, last [, step]
			Cursor cursor = new Cursor(line);
			lastLine = cursor.parseNumber();
			var = new Variable(cursor.parseVar(), variables);
			cursor.expect('=');
			first = new Expression(cursor.parseExpression(), variables);
			cursor.expect(',');
			last  = new Expression(cursor.parseExpression(), variables);
			if (cursor.at(',')) {
				cursor.expect(',');
				step = new Expression(cursor.parseExpression(), variables);
			}
			else {
				step = new Expression("1", variables);
			}
		}
	
		void emit(MakoRom rom) {
			// i = first
			first.emit(rom);
			var.emit(rom);
			rom.add(OP_STOR, MakoRom.Type.Code);

			// skip initial increment:
			rom.add(OP_JUMP, MakoRom.Type.Code);
			int a = rom.size();
			rom.add(-1, MakoRom.Type.Code);
			formulas.get(lastLine).gotoAfter = rom.size();

			// i += step
			var.emit(rom);
			rom.add(OP_LOAD, MakoRom.Type.Code);
			step.emit(rom);
			rom.add(OP_ADD,  MakoRom.Type.Code);
			var.emit(rom);
			rom.add(OP_STOR, MakoRom.Type.Code);
			rom.set(a, rom.size(), MakoRom.Type.Code);

			// if (i <= last) goto loop start
			var.emit(rom);
			rom.add(OP_LOAD,   MakoRom.Type.Code);
			last.emit(rom);
			rom.add(OP_SGT,    MakoRom.Type.Code);

			// jump to resume address
			rom.add(OP_JUMPIF,  MakoRom.Type.Code);
			int index = program.indexOf(formulas.get(lastLine));
			rom.add(program.get(index + 1).address, MakoRom.Type.Code);
		}
	}
	
	class IfFormula extends Formula {
		int trueBranch;
		int falseBranch;
		int comparison;
		Expression a;
		Expression b;

		IfFormula(String line) {
			// if ( expression comparator expression ) truebranch, falsebranch
			Cursor cursor = new Cursor(line);

			Cursor condCursor = cursor.parseParens();
			a = new Expression(condCursor.parseExpression(), variables);
			if      (condCursor.line.startsWith(">=")) { comparison = 1; condCursor.skip(2); condCursor.trim(); }
			else if (condCursor.line.startsWith(">"))  { comparison = 0; condCursor.skip(1); condCursor.trim(); }
			else if (condCursor.line.startsWith("="))  { comparison = 2; condCursor.skip(1); condCursor.trim(); }
			else { condCursor.fail(); }
			b = new Expression(condCursor.parseExpression(), variables);

			trueBranch  = cursor.parseNumber();
			cursor.expect(',');
			falseBranch = cursor.parseNumber();
		}
	
		void emit(MakoRom rom) {
			a.emit(rom);
			b.emit(rom);
			if (comparison == 0) {
				// greater-than
				rom.add(OP_SGT,    MakoRom.Type.Code);
				rom.add(OP_JUMPIF, MakoRom.Type.Code);
			}
			else if (comparison == 1) {
				// greater-than-or-equal-to
				rom.add(OP_SLT,    MakoRom.Type.Code);
				rom.add(OP_JUMPZ,  MakoRom.Type.Code);
			}
			else if (comparison == 2) {
				// equal-to
				rom.add(OP_XOR,    MakoRom.Type.Code);
				rom.add(OP_JUMPZ,  MakoRom.Type.Code);
			}

			rom.add(formulas.get(trueBranch).address, MakoRom.Type.Code);
			rom.add(OP_JUMP, MakoRom.Type.Code);
			rom.add(formulas.get(falseBranch).address, MakoRom.Type.Code);
		}
	}

	class GoToFormula extends Formula {
		int target;
	
		GoToFormula(String line) {
			// go to number
			try { target = Integer.parseInt(line); }
			catch(NumberFormatException e) { fail(); } 
		}
	
		void emit(MakoRom rom) {
			rom.add(OP_JUMP, MakoRom.Type.Code);
			rom.add(formulas.get(target).address, MakoRom.Type.Code);
		}
	}
	
	class StopFormula extends Formula {
		StopFormula(String line) {
			// stop
			if (line.length() > 0) { fail(); }
		}
	
		void emit(MakoRom rom) {
			rom.add(OP_JUMP, MakoRom.Type.Code);
			rom.add(-1, MakoRom.Type.Code);
		}
	}

	class ReadFormula extends Formula {
		List<Variable> args = new ArrayList<Variable>();

		ReadFormula(String line) {
			// read varname [, and, more, ...]
			Cursor cursor = new Cursor(line);
			while(true) {
				args.add(new Variable(cursor.parseVar(), variables));
				if (cursor.done())   { break; }
				if (!cursor.at(',')) { break; }
				cursor.expect(',');
			}
		}
	
		void emit(MakoRom rom) {
			for(Variable v : args) {
				rom.add(OP_CALL, MakoRom.Type.Code);
				rom.add(INPUT_SUBROUTINE, MakoRom.Type.Code);
				v.emit(rom);
				rom.add(OP_STOR, MakoRom.Type.Code);
			}
		}
	}
	
	class PrintFormula extends Formula {
		List<Variable> args = new ArrayList<Variable>();

		PrintFormula(String line) {
			// print varname [, and, more, ...]
			Cursor cursor = new Cursor(line);
			while(true) {
				args.add(new Variable(cursor.parseVar(), variables));
				if (cursor.done())   { break; }
				if (!cursor.at(',')) { break; }
				cursor.expect(',');
			}
		}
	
		void emit(MakoRom rom) {
			for(Variable v : args) {
				v.emit(rom);
				rom.add(OP_LOAD, MakoRom.Type.Code);
				rom.add(OP_CALL, MakoRom.Type.Code);
				rom.add(PRINT_SUBROUTINE, MakoRom.Type.Code);
			}
			rom.add(OP_CONST, MakoRom.Type.Code);
			rom.add(10,       MakoRom.Type.Code);
			rom.add(OP_CONST, MakoRom.Type.Code);
			rom.add(CO,       MakoRom.Type.Code);
			rom.add(OP_STOR,  MakoRom.Type.Code);
		}
	}

	class NopFormula extends Formula {
		void emit(MakoRom rom) {
			// do nothing.
		}
	}

	class DimensionFormula extends Formula {
		Variable v;
		int d1;
		Integer d2;
		Integer d3;
		
		DimensionFormula(String line) {
			// dimension varname(one [, two][, three])
			Cursor cursor = new Cursor(line);
			String name = cursor.parseVarName();
			if (!variables.containsKey(name)) {
				v = new Variable(line, variables);
			}

			Cursor argCursor = cursor.parseParens();
			d1 = argCursor.parseNumber();
			if (argCursor.at(',')) {
				argCursor.expect(',');
				d2 = argCursor.parseNumber();
			}
			if (argCursor.at(',')) {
				argCursor.expect(',');
				d3 = argCursor.parseNumber();
			}

			v = variables.get(name);
			if (d3 != null) {
				if (v.args.size() != 3) { fail(); }
				v.value = new int[d1][d2][d3];
			}
			else if (d2 != null) {
				if (v.args.size() != 2) { fail(); }
				v.value = new int[d1][d2];
			}
			else {
				if (v.args.size() != 1) { fail(); }
				v.value = new int[d1];
			}
		}

		void emit(MakoRom rom) {
			// do nothing- this construct only
			// has compile-time meaning.
		}
	}
}

abstract class Formula {
	Integer number;			// fortran 'formula number'
	Integer gotoAfter;		// jump to an address after this line?
	Integer address = -1;	// compiled starting address

	static void fail() {
		throw new Error("SYNTAX ERROR. WHOOPSIE-DAISY.");
	}

	abstract void emit(MakoRom rom);
}