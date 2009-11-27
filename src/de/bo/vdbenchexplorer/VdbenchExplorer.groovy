package de.bo.vdbenchexplorer;

import groovy.lang.GroovyShell;
import groovy.swing.SwingBuilder;
import java.util.regex.Matcher;
import java.util.Date;
import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.plaf.UIResource;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;
import javax.swing.table.TableModel;
import javax.swing.event.TableColumnModelEvent;
import javax.swing.event.TableColumnModelListener;
import javax.swing.event.TableModelListener;
import jplot.*;

enum Type { DATETIME, TIME, LABEL, INT, FLOAT };

/* TODO: unsolved bugs
 * - closing a plot window throws an exception
 * Exception in thread "AWT-EventQueue-0" java.lang.NullPointerException: Cannot invoke method dispose() on null object
 * ...
 * at de.bo.vdbenchexplorer.Plot$1.windowClosing(VdbenchExplorer.groovy:809)
 * - row filtering: removing all data leads to orphaned plot windows and an exception:
 * Exception in thread "AWT-EventQueue-0" org.codehaus.groovy.runtime.InvokerInvocationException: groovy.lang.GroovyRuntimeException: Infinite loop in 0.upto(-1)
 * ...
 * at de.bo.vdbenchexplorer.ProxyColumn.getDoubles(VdbenchExplorer.groovy:641)
 * - sometimes when moving columns an exception occurs:
 * Exception in thread "AWT-EventQueue-0" java.lang.NullPointerException: Cannot invoke method getRow() on null object
 * ...
 * at de.bo.vdbenchexplorer.ProxyColumn$getRow.call(Unknown Source)
 * at de.bo.vdbenchexplorer.Table.getValueAt(VdbenchExplorer.groovy:55)
 */

abstract class Table implements TableModel {
	String name;
	String description;
	def cols=[];

	// TableModel methods
	
	int getColumnCount() {
		return cols.size();
	}

	String getColumnName(int col) {
		return cols[col].columnHead.name;
	}

	int getRowCount() {
		return cols*.length().max();
	}

	Object getValueAt(int row, int col) {
		return cols[col].getRow(row).getTypedVal();
	}

	boolean isCellEditable(int row, int col) {
		// Cells are not editable
		return false;
	}

	void setValueAt(Object o, int row, int col) {
		// Do nothing
	}

	Class getColumnClass(int col) {
		return cols[col].getRow(0).getTypedVal().class;
	}
	
	void addTableModelListener(TableModelListener l) {
		// Do nothing
	}

	void removeTableModelListener(TableModelListener l) {
		// Do nothing
	}

	// Additional abstract methods

	abstract void update();
	
	// Own methods

	void add(Column c) {
		cols << c;
	}
	
	Column getColumn(int col) {
		return cols[col];
	}

	Column getColumnByName(String name) {
		return cols.find { it.columnHead.name == name };
	}

	int getColumnNr(Column c) {
		def ret=(0..(cols.size()-1)).find { cols[it] == c };
		if (ret!=null) {
			return ret;
		} else {
			return -1;
		}
	}

	Cell[] getRow(int row) {
		cols*.getRow(row).toList();
	}

	Cell getCellAt(int row, int col) {
		return cols[col].getRow(row);
	}

	String[] boringColumns() {
		return (String[])[];
	}
}

class SimpleTable extends Table {
	void update() {};
}

class VdbenchFlatfileTable extends Table {
	def h=[:], heads;
	
	VdbenchFlatfileTable(String file) {
		Matcher m;
		String[] ls = new File(file).readLines();
		def t=this;
		ls.each {
			m = (it =~ /^\* (\S+) *: (.*)$/);
			if (m.count>0 && !it.startsWith("* 'n/a'")) {
				h[m[0][1]]=m[0][2];
			}
		}
		heads=ls.grep({it =~ /tod/})[0].split();
		heads.each {
			cols << new SimpleColumn(new ColumnHead(name:it, 
					description:h[it]));
		}
		ls.each {
			if (it =~ /^[0-9]/) {
				def row=it.split();
				1.upto(row.size()) { col ->
					cols[col-1].add(new Cell(cols[col-1], cols[col-1].length(),
							row[col-1]));
				}
			}
		}
		cols.each {
			it.columnType=it.guessType();
			it.initSymbols();
		}
		cols[0].columnHead.description="Time/s";
	}

	// TODO: reinit VdbenchFlatfileTable
	void update() {};

	String[] boringColumns() {
		def list = [ "reqrate", "xfersize", "lunsize", "version", "ks_rate", 
		             "ks_resp", "ks_svct", "ks_mb", "ks_read%", "ks_bytes" ];
		return list;
	}
}

class SortedTable extends Table {
	Table masterTable;
	JTable jt=null;
	RowMap rm;
	
	SortedTable(Table t) {
		this(t, null);
	}

	SortedTable(Table t, JTable jt) {
		this.masterTable=t;
		this.jt=jt;
		name=t.name;
		description=t.description;
		init();
	}

	private void init() {
		def c;
		cols = [];
		rm = new RowMap(masterTable.cols[0].length());
		println "this="+this+" c="+masterTable.getColumnCount()+" m="+masterTable;
		masterTable.cols.each { col ->
			c = new ProxyColumn(rm, col);
			cols << c;
		}
	}

	void update() {
		init();
		sort();
	}
	
	void setJTable(JTable jt) {
		this.jt = jt;
	}

	/* Sort all columns from top to down and from left to right:
	 * The leftmost column is sorted fully, each right neighbour is only
	 * sorted among those rows in which the values of the left neighbour 
	 * column are equal etc. In every column, sorting is in ascending order.
	 * 
	 * Column reordering in the JTable is supported, i.e. the order in which
	 * the columns are sorted is the visual order of the columns in the
	 * JTable.
	 * 
	 * We do not create new SortedColumn objects because we want to keep
	 * their object ids. Instead we access the rows via a mapping.
	 */
	private void sort() {
		def mcol, tmp0, tmp1, tmp2, col = 0;
		def stack = [];

		def rowPermutation = (0..cols[0].length()-1).toArray();
		//println "rP="+rowPermutation;
		stack << [rowPermutation];

		while (col<cols.size && stack[col].size()<cols[0].length()) {
			mcol = mapColumn(col);
			stack[col+1]=[];
			//println "c="+col;

			tmp0 = [];
			stack[col].each { range ->
				//println "r="+range;
				if (range.size()==1) {
					stack[col+1] << range;
					tmp0 += range.toList();
					return;
				}
				range=range.sort() { a,b ->
					cols[mcol].getRealRow(a).getTypedVal() <=> 
					cols[mcol].getRealRow(b).getTypedVal()
				};
				//println "r="+range;
				/* Don't know why toList() is necessary, but, nevertheless,
				 * it is.
				 */
				tmp0 += range.toList();
				tmp1 = [];
				tmp2 = cols[mcol].getRealRow(range[0]).val;
				//println "tmp2="+tmp2;
				range.each { it ->
					//println "it="+it;
					if (tmp2!=cols[mcol].getRealRow(it).val) {
						stack[col+1] << tmp1;
						tmp1 = [];
						tmp1 << it;
						tmp2 = cols[mcol].getRealRow(it).val;
						//println "tmp2="+tmp2+" it="+it;
 					} else {
 						tmp1 << it;
 					}
				};
				stack[col+1] << tmp1;
			};
			rowPermutation=tmp0;
			//println "tmp0="+tmp0;
			//println "st="+stack[col+1];
			col++;
		}
		//println "frP="+rowPermutation;
		
		rm.virt2real = rowPermutation;
		rm.real2virt = [];
		(0..cols[0].length()-1).each {
			rm.real2virt[rowPermutation[it]]=it;
		}
		
		return;
	}

	int length() {
		return masterTable.cols[0].length();
	}
	
	private int mapColumn(int col) {
		if (jt==null) {
			return col;
		} else {
			return jt.convertColumnIndexToModel(col);
		}
	}
}

class RowFilteredTable extends Table {
	Table masterTable;
	JTable jt=null;
	RowMap rm;
	def closures=[];
	def filters=[];

	RowFilteredTable(Table t) {
		this(t, null);
	}

	RowFilteredTable(Table t, JTable jt) {
		this.masterTable=t;
		this.jt=jt;
		name=t.name;
		description=t.description;
		init();
	}

	private void init() {
		def c;
		cols = [];
		rm = new RowMap(masterTable.cols[0].length());
		println "this="+this+" c="+masterTable.getColumnCount()+" m="+masterTable;
		masterTable.cols.each { col ->
			c = new RowFilteredColumn(rm, col);
			cols << c;
			if (!filters[cols.size()-1]) { filters[cols.size()-1] = [] };
			c.filtered=(filters[cols.size()-1].size()>0);
		}
		println "v2r="+rm.virt2real+" r2v="+rm.real2virt;
	}

	void update() {
		init();
		applyFilters();
	}
	
	void addFilter(int col, String[] vals, boolean inverse) {
		def c = { row ->
			def val = masterTable.getColumn(col).getRow(row).val;
			boolean ret=vals.grep(val);
			ret=inverse?!ret:ret;
			//println "row="+row+" val="+val+" ret="+ret+" vals="+vals;
			return ret;
		};
		closures << c;
		filters[col] << c;
		cols[col].filtered = true;
		return;
	}

	void addFilter(int col, String val, boolean inverse) {
		def c = { row ->
			boolean ret = (masterTable.getColumn(col).getRow(row).val == val);
			ret=inverse?!ret:ret;
			//println "row="+row+" val="+val+" ret="+ret;
			return ret;
		};
		closures << c
		filters[col] << c;
		cols[col].filtered = true;
		return;
	}

	void removeAllFilters() {
		closures = [];
		cols.each { it.filter = [] };
		filters = [];
		cols.each { it.filtered = false }
	}

	void removeColFilters(int col) {
		filters[col].each {
			closures.remove(it);
		}
		filters[col] = [];
		cols[col].filtered = false;
	}

	int filterCount() {
		return closures.size();
	}
	
	boolean matches(int row) {
		if (!closures) { return false; }
		for(closure in closures) {
			if (closure(row)) {
				return true;
			}
		}
		return false;
	}
	
	void applyFilters() {
		int row=0;
		rm.clear();
		0.upto(masterTable.getColumn(0).length()-1) {
			//println "it="+it+" row="+row;
			if (!matches(it)) {
				//println masterTable.getRow(it).toString();
				rm.map(row, it);
				row++;
			}
		}
		println "v2r="+rm.virt2real+" r2v="+rm.real2virt;
	}

	int length() {
		return rm.virt2real.size();
	}
}

class ColumnFilteredTable extends Table {
	Table masterTable;
	def removed = [];
	
	ColumnFilteredTable(Table t) {
		this.masterTable = t;
		this.name=t.name;
		this.description=t.description;
		init();
	}

	private void init() {
		cols = [];
		masterTable.cols.each { col ->
			cols << col;
		}
	}

	// Implement abstract method from Table
	
	void update() {
		cols.each {
			if (it instanceof SyntheticColumn) {
				it.update();
			}
		}
		init();
	}

	// Own methods

	void addSyntheticColumn(Column[] baseCols, String expr) {
		cols << new SyntheticColumn(baseCols, expr);
	}

	void removeColumn(Column col) {
		removed << col;
		cols.remove(col);
	}

	void removeColumnsByNames(String[] names) {
		def l;
		names.each { name ->
			l = cols.findAll { it.columnHead.name == name };
			l.each { cols.remove(it) }
		}
	}
	
	void removeColumn(int col) {
		removed << cols[col];
		cols[col]=[];
	}
}

abstract class Column {
	private Type columnType = Type.LABEL;
	ColumnHead columnHead;
	// masked is not in use currently
	boolean masked = false;
	// plotted: This column is to be plotted
	boolean plotted = false;
	/* groupby: Plot several curves discriminated by the values in this column
	 * TODO: It usually does not make sense to have both plotted and groupby 
	 * set to true.
	 */ 
	boolean groupby = false;
	def d2l = [:], l2d = [:], symbols;
	
	abstract Cell getRow(int row);
	abstract String[] getVals();
	abstract void setRow(int row, Cell c);
	abstract void add(Cell c);
	abstract void removeAll();
	abstract int length();
	abstract int cardinality();
	abstract String[] distinctVals();
	abstract double[] getDoubles();

	static Type guessType(String[] l) {
		if (l.grep(~/-?\d+/).size()==l.size()) {
			return Type.INT;
		} else if (l.grep(~/-?\d+\.?\d*([fFdD]|[eEgG]-?\d+)?/).size()==
			l.size()) {
			return Type.FLOAT
		} else if (l.grep(~/\d{1,2}:\d{2}:\d{2}\.\d{3}/).size()==l.size()) {
			return Type.TIME;
		} else {
			return Type.LABEL;
		}
	}

	Type getColumnType() {
		return columnType;
	}

	void setColumnType(Type t) {
		columnType = t;
	}
	
	Column add(Column c) {
		 int row = length();
		 c*.vals.each {
			 add(new Cell(this, row++, it));
		 }
		 return this;
	}
}

class SimpleColumn extends Column {
	def cells=[];
	
	SimpleColumn(ColumnHead ch) {
		this.columnHead = ch;
	}

	SimpleColumn(String head, String desc, String[] vals) {
		this.columnHead = new ColumnHead(name:head, description:desc);
		vals.each {
			cells << new Cell(this, cells.size(), it);
		}
	}
	
	SimpleColumn(String head, String desc, Type type, String[] vals) {
		this.columnHead = new ColumnHead(name:head, description:desc);
		this.columnType = type;
		vals.each {
			cells << new Cell(this, cells.size(), it);
		}
	}
	
	void add(Cell c) {
		this.cells << c;
	}

	void removeAll() {
		cells=[];
	}

	void setRow(int row, Cell c) {
		cells[row]=c;
	}

	Cell getRow(int row) {
		return cells[row];
	}

	String[] getVals() {
		return cells*.val;
	}

	int length() {
		return cells.size();
	}

	Type guessType() {
		return guessType((String[])cells*.val);
	}

	int cardinality() {
		return cells*.val.sort().unique().size();
	}

	String[] distinctVals() {
		return (String[]) cells*.val.sort().unique();
	}

	void initSymbols() {
		// We need a toList(), otherwise sort(), unique() strips the list x
		symbols = cells*.getTypedVal().toList().sort().unique();
		(0..symbols.size()-1).each {
			d2l[(double) it] = symbols[it];
			l2d[symbols[it]] = (double) it;
		}
	}

	double[] getDoubles() {
		def x = cells*.getTypedVal();
		double[] t;

		if (columnType == Type.TIME) {
			def xmin = x*.getTime().min();
			t = x.collect { it = (double)(it.getTime()-xmin)/1000; };
		} else if (columnType == Type.LABEL) {
			t = x.collect { it1 -> l2d[it1] }
		} else {
			t = (double[]) x;
		}
		return t;
	}
}

class ProxyColumn extends Column {
	protected RowMap rm;
	protected Column realCol;
	
	ProxyColumn(RowMap rm, Column c) {
		this.realCol = c;
		this.rm = rm;
		this.columnHead = c.columnHead;
	}

	// Overwritten methods
	void add(Cell c) {
		realCol.add(c);
	}

	void removeAll() {
		realCol.removeAll();
	}

	void setRow(int row, Cell c) {
		realCol.setRow(rm.virt2real(row), c);
	}

	Cell getRow(int row) {
		return realCol.getRow(rm.virt2real(row));
	}

	String[] getVals() {
		def vals = realCol.getVals();
		def r = (0..length()-1).collect { rm.virt2real(it) }.toList();
		//println "V="+vals;
		//println "R="+r;
		return vals[r];
	}

	int length() {
		return rm.virt2real.size();
	}

	Type guessType() {
		return realCol.guessType();
	}

	int cardinality() {
		return getVals().toList().sort().unique().size();
	}

	String[] distinctVals() {
		return (String[]) getVals().toList().sort().unique();
	}

	Type getColumnType() {
		return realCol.columnType;
	}

	void setColumnType(Type t) {
		realCol.columnType=t;
	}

	Object[] getSymbols() {
		return realCol.symbols;
	}

	HashMap getL2d() {
		return realCol.l2d;
	}
	
	HashMap getD2l() {
		return realCol.d2l;
	}

	boolean isPlotted() {
		return realCol.plotted;
	}

	void setPlotted(boolean t) {
		realCol.plotted = t;
	}
	
	boolean isGroupby() {
		return realCol.groupby;
	}

	void setGroupby(boolean t) {
		realCol.groupby = t;
	}
	
	boolean isFiltered() {
		return realCol.filtered;
	}
	
	double[] getDoubles() {
		def dr = realCol.getDoubles();
		def dv = [];
		0.upto(length()-1) {
			dv << dr[rm.virt2real(it)];
		}
		return dv;
	}

	// Own methods
	Cell getRealRow(int row) {
		return realCol.getRow(row);
	}
}

class RowFilteredColumn extends ProxyColumn {
	boolean filtered = false;
	
	RowFilteredColumn(RowMap rm, Column c) {
		super(rm, c);
	}
}

/* Create synthetic columns from other columns, using a closure
 */
class SyntheticColumn extends SimpleColumn {
	Column[] baseColumns;
	String formula;
	Binding b;
	GroovyShell gs;

	SyntheticColumn(Column[] cols, String e) {
		super(new ColumnHead(name:e, description:e));
		assert cols.grep { it.length() != cols[0].length() }.size() == 0;
		this.baseColumns = cols;
		this.formula = e;
		b = new Binding();
		gs = new GroovyShell(b);
		init();
	}

	private void init() {
		String val;
		0.upto(baseColumns[0].length()-1) { r ->
			b.col = [:];
			baseColumns.each { c -> 
				b.col[c.columnHead.name]=c.getRow(r).getTypedVal();
			}
			/* GroovyShell does the hard work for us. Since GroovyShell
			 * allows to excute any kind of code, this could be dangerous.
			 * But nobody can force the user to enter harmful expressions, 
			 * so this is safe. And harmful typos are very improbable.
			 */
			
			val = gs.evaluate(formula).toString();
			cells << new Cell(this, r, val); 
		}
		columnType=this.guessType();
	}

	void update() {
		init();
	}

	// Overwritten methods
	
	void add(Cell c) {
		throw(new Exception("You can not modify synthetic columns"));
	}
	
	void setRow(int row, Cell c) {
		throw(new Exception("You can not modify synthetic columns"));
	}
	
	void removeAll() {
		throw(new Exception("You can not modify synthetic columns"));
	}
}

class RowMap {
	def real2virt = [:];
	def virt2real = [:];

	RowMap() {}
	
	RowMap(int n) {
		init(n);
	}
	
	void init(int n) {
		real2virt = 0..(n-1);
		virt2real = 0..(n-1);
	}
	
	int real2virt(int row) {
		return real2virt[row];
	}
	
	int virt2real(int row) {
		return virt2real[row];
	}

	void map(int v, int r) {
		virt2real[v]=r;
		real2virt[r]=v;

	}

	void clear() {
		real2virt = [:];
		virt2real = [:];
	}
}

class ColumnHead {
	String name;
	String description;
}

class Cell {
	int row;
	Column column;
	String val;

	Cell(Column c, int row, String val) {
		this.column = c;
		this.row = row;
		this.val = val;	
	}

	Type getType() {
		return column.columnType;
	}

	Object getTypedVal() {
		switch(column.columnType) {
		case Type.LABEL:
			return (String)val;
			break;
		case Type.INT:
			return val.toInteger();
			break;
		case Type.FLOAT:
			return val.toDouble();
			break;
		case Type.DATETIME:
			return null;
			break;
		case Type.TIME:
			if (val =~ /\d{1,2}:\d{2}:\d{2}\.\d{3}/) {
				return Date.parse("HH:mm:ss.SSS", val);
			}
			return (Date)val;
			break;
		}
	}
}

class Plot {
	JFrame plotFrame;
	JPanel p;
	Column cx;
	/* Must be of same size as cx */
	Column cy;
	/* Use the discriminating values of the following column to plot
	 * (cx,cy) as separate lines with distinct labels and colors.
	 * Must be of same size as cx;
	 * If null do not use groupby;
	 */
	Column groupby = null;
	String description = null;

	// Makes no sense, do not use
	Plot() {
		plotFrame = new JFrame();
		p = new JPanel(new BorderLayout());
		plotFrame.getContentPane().add(p);
	}	

	Plot(Column c1, Column c2) {
		this(c1, c2, null);
	}	

	Plot(Column c1, Column c2, Column g) {
		assert c1.length() == c2.length();
		this.cx = c1;
		this.cy = c2;
		this.groupby = g;

		plotFrame = new JFrame();
		p = new JPanel(new BorderLayout());
		plotFrame.getContentPane().add(p);
		init();
	}	

	void reinit(Column c1, Column c2) {
		assert c1.length() == c2.length();
		this.cx = c1;
		this.cy = c2;
		init();
	}
	
	void reinit(Column c1, Column c2, Column g) {
		this.groupby = g;
		reinit(c1, c2);
	}
	
	void groupby(Column g) {
		this.groupby = g;
		init();
	}

	void redraw() {
		init();
	}
	
	private void init() {
		double[] x = cx.getDoubles();
		double[] y = cy.getDoubles();

		p.removeAll();
		
		GraphSettings gs = new GraphSettings();
		gs.setMinValue(GraphSettings.X_AXIS, x.toList().min());
		gs.setMaxValue(GraphSettings.X_AXIS, x.toList().max());
		gs.setMinValue(GraphSettings.Y_AXIS, y.toList().min());
		gs.setMaxValue(GraphSettings.Y_AXIS, y.toList().max());

		GraphLabel l=new GraphLabel(GraphLabel.XLABEL, 
				cx.columnHead.description);
		gs.addLabel(l);
		l=new GraphLabel(GraphLabel.YLABEL, cy.columnHead.description);
		l.setRotation(Math.PI*1.5);
		gs.addLabel(l);
		
		/* TODO: When the window is resized, the axis is scaled 
		 * correctly but the labels do not and loose their positions
		 * relative to the axis tick marks.
		 * TODO: Plotting long labels is still far from optimal.
		 */ 
		if (cx.columnType == Type.LABEL) {
			gs.setDrawTicLabels(GraphSettings.X_AXIS, false);
			cx.symbols.each {
				// Use GraphLabel.DATA when you specify points in data 
				// coordinates
				l = new GraphLabel(GraphLabel.DATA, it);
				l.setDataLocation(cx.l2d[it], 0.0D);
				gs.addLabel(l);
			}
		} else {
			gs.setDrawTicLabels(GraphSettings.X_AXIS, true);
		}
		if (cy.columnType == Type.LABEL) {
			gs.setDrawTicLabels(GraphSettings.Y_AXIS, false);
			cy.symbols.each {
				// Use GraphLabel.DATA when you specify points in data 
				// coordinates
				l = new GraphLabel(GraphLabel.DATA, it);
				l.setDataLocation(0.0D, cy.l2d[it]);
				gs.addLabel(l);
			}
		} else {
			gs.setDrawTicLabels(GraphSettings.Y_AXIS, true);
		}		

		plotFrame.title=(description!=null) ? description :
				cx.columnHead.name+" - "+cy.columnHead.name;
		plotFrame.addWindowListener(new WindowAdapter() {
		    public void windowClosing(WindowEvent e) {
		      plotFrame.dispose();
		      plotFrame = null;
		    }
		  });

		def da = [];
		if (groupby!=null) {
			int count=0;
			groupby.distinctVals().each { val ->
				def points = [];
				DataArray d = new DataArray();
				0.upto(cx.length()-1) {
					if (groupby.getRow(it).val==val) {
						//println it+" "+x[it]+" "+y[it]+" "+" "+groupby.getRow(it).val+" "+val;
						points << [x[it], y[it]];
					}
				}
				//points = points.sort { it[0] };
				points.each { d.addPoint(it[0], it[1]) };
				d.setDrawSymbol(true);
				d.setSymbol(2);
				d.setColor(gencolor(count++, groupby.cardinality()));
				d.name=groupby.columnHead.name+"="+val;
				d.drawLegend=true;
				da << d; 
			};
		} else {
			def points = [];
			DataArray d = new DataArray();
			0.upto(cx.length()-1) {
				points << [x[it], y[it]];
			}
			//points = points.sort { it[0] };
			points.each { d.addPoint(it[0], it[1]) };
			d.setDrawSymbol(true);
			d.setSymbol(2);
			d.drawLegend=false;
			da << d;
		}

		Vector v = new Vector();
		da.each { it -> v.add(it) };

		Graph_2D graph = new Graph_2D(gs);
		graph.show(v);

		p.add(graph,BorderLayout.CENTER);
		plotFrame.pack();
		plotFrame.show();
	}

	void kill() {
		if (plotFrame!=null) {
			plotFrame.dispose();
			plotFrame=null;
		}
	}

	private Color gencolor(int i, int n) {
		return new Color((float)(i+1)/(n+2), (float)(i+1)/(n+2), 
				(float)(i+1)/(n+2));
	}
}

/* Listeners
 * 
 */

// Needed for resizing the frame such that the JScrollPane's size is updated
class ResizeListener extends ComponentAdapter {
	Closure closure;
	
	ResizeListener(Closure c) {
		closure=c;
	}
	
	public void componentResized(ComponentEvent e) {
		// Don't forget the parantheses!
		closure();
    }
}

class PopupListener extends MouseAdapter {
	Closure closure;

	PopupListener(Closure c) {
		closure=c;
	}
	
    public void mousePressed(MouseEvent e) {
        maybeShowPopup(e);
    }

    public void mouseReleased(MouseEvent e) {
        maybeShowPopup(e);
    }

    private void maybeShowPopup(MouseEvent e) {
        if (e.isPopupTrigger()) {
        	closure(e);
        }
    }
}

class TCMListener implements TableColumnModelListener {
	Closure closure;

	TCMListener(Closure c) {
		closure=c;
	}

	void columnAdded(TableColumnModelEvent e) {}
	void columnRemoved(TableColumnModelEvent e) {}
	void columnChanged(TableColumnModelEvent e) {}
	void columnMarginChanged(javax.swing.event.ChangeEvent e) {}
	void columnSelectionChanged(javax.swing.event.ListSelectionEvent e) {}
	
	void columnMoved(TableColumnModelEvent e) {
		if (e.fromIndex!=e.toIndex) {
			//println "column moved from "+e.fromIndex+" to "+e.toIndex;
			//Don't forget the parantheses!
			closure();
		}
	}
}

/* End of Listeners 
 * 
 */

/* Renderers
 * 
 */

class CustomHeaderRenderer extends DefaultTableCellRenderer 
	implements UIResource {
    public Component getTableCellRendererComponent(JTable table, Object value,
             boolean isSelected, boolean hasFocus, int row, int column) {
    	if (table != null) {
    		JTableHeader header = table.getTableHeader();
    		if (header != null) {
    	        int col=table.convertColumnIndexToModel(column);
    	        
    			if (((Table)table.model).getColumn(col).plotted) {
    				if (((Table)table.model).getColumn(col).groupby) {
    					if (((Table)table.model).getColumn(col).filtered) {
    						setForeground(Color.ORANGE);
    						setBackground(header.foreground);
    					} else {
    						setForeground(Color.YELLOW);
    						setBackground(header.foreground);    						
    					}
    				} else {
    					if (((Table)table.model).getColumn(col).filtered) {
    						setForeground(Color.RED);
    						setBackground(header.foreground);
    					} else {
    						setForeground(header.background);
    						setBackground(header.foreground);    						
    					}
    				}
    			} else if (((Table)table.model).getColumn(col).groupby) {
					if (((Table)table.model).getColumn(col).filtered) {
						setBackground(Color.ORANGE);
						setForeground(header.foreground);						
					} else {
						setBackground(Color.YELLOW);
						setForeground(header.foreground);
					}
    	        } else {
					if (((Table)table.model).getColumn(col).filtered) {
						setBackground(Color.RED);						
						setForeground(header.foreground);
					} else {
						setBackground(header.background);
						setForeground(header.foreground);
					}
    	        }
    		}
    	}

        setText((value == null) ? "" : value.toString());
        setToolTipText(getText());
        setBorder(UIManager.getBorder("TableHeader.cellBorder"));
        return this;
    }
}

/* End of Renderers
 * 
 */

class JTable2 extends JTable {
	int margin = 5;
	TableModel tm;

	JTable2(TableModel tm) {
		super(tm);
		this.tm = tm;
		this.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
		def tcr = new CustomHeaderRenderer();
		
		0.upto(tm.columnCount-1) { col ->
			this.getColumnModel().getColumn(col).preferredWidth=
				this.columnWidth(col);
			this.getColumnModel().getColumn(col).headerRenderer=tcr;
		}
		
	}

	int columnWidth(int col) {
		int w = 0;
		def tc = this.getColumnModel().getColumn(col);

		// TODO: What is the equivalent of prepareRenderer for the header?
		// new JTableHeader(tcm).getHeaderRect(col) ?
		def r = tc.headerRenderer;
		if (tc.headerRenderer == null) {
			r = this.tableHeader.defaultRenderer;
		}
		w = Math.max(w, r.preferredSize.width);
		
		0.upto(tm.rowCount-1) { row ->
			def tcr = this.getCellRenderer(row, col);
			this.prepareRenderer(tcr, row, col);
			def wc = tcr.preferredSize.width;
			w = Math.max(w, wc);
		}
		
		return w+2*margin;
	}
}

class TableStack {
	def tables = [];
	def t2 = [:]

	void updateUpwardsFrom(Table t) {
		int i = (0..(tables.size()-1)).find { tables[it] == t };
		i.upto(tables.size()-1) {
			tables[it].update();
		}
	}

	void add(Table t) {
		tables << t;
	}

	void add(Table t, String name) {
		t2[name] = t;
		tables << t;
	}

	void add(Class c) {
		tables << c.newInstance(tables[-1]);
	}

	void add(Class c, String name) {
		def t = c.newInstance(tables[-1]);
		t2[name] = t;
		tables << t;
	}

	Table top() {
		return tables[-1];
	}

	Table findByName(String s) {
		return t2[s];
	}
	
	Table findByClass(Class c) {
		return tables.find { it.class == c };
	}
}

class VdbenchExplorerGUI {
	def swing;
	def frame;
	def plots = [];
	def fixedplots = [];
	int margin = 10;
	private JTable2 jt2;
	private Column groupby = null;
	private int groupbylimit = 10;
	private int delay = 500;
	private java.util.Timer redrawRequest = null;
	private TableStack ts = new TableStack();

    private final def exit;
    private final def open;
    
	VdbenchExplorerGUI() {
		swing = new SwingBuilder();

		exit = swing.action(name:'Exit', closure:{System.exit(0)});
		open = swing.action(name:'Open', closure:{
//			def t1 = new VdbenchFlatfileTable("/Users/jf/BO/masterdata/XXX/flatfile.html");
			def t1 = new VdbenchFlatfileTable("/Users/jf/BO/masterdata/XXX/flatfile.html");

			ts.add(t1, "Base");
			ts.add(ColumnFilteredTable.class, "Boring");
			ts.findByName("Boring").
				removeColumnsByNames(ts.findByName("Base").boringColumns());
			ts.add(RowFilteredTable.class, "RowFilter");
			ts.add(SortedTable.class, "Sorter");
			ts.add(ColumnFilteredTable.class, "Synthetic");
			
			jt2 = new JTable2(ts.top());
			
			ts.findByName("Sorter").setJTable(jt2);

			// Registering for right-clicks on the TableHeader
			jt2.tableHeader.addMouseListener(new PopupListener({
				// Find the column in which the MouseEvent was triggered
				int col = jt2.getColumnModel().getColumnIndexAtX(it.getX());
				createPopupMenu(jt2.convertColumnIndexToModel(col)).
					show((Component) it.getSource(), it.getX(), it.getY());
			}));

			jt2.addMouseListener(new PopupListener({
				// Find the cell in which the MouseEvent was triggered
				int row = jt2.rowAtPoint(it.point);
				int col = jt2.convertColumnIndexToModel(
						jt2.columnAtPoint(it.point));
				createPopupMenuCell(row, col).
					show((Component) it.getSource(), it.getX(), it.getY());
			}))

			jt2.columnModel.addColumnModelListener(new TCMListener({
				/* When a column is dragged across the table we don't want
				 * to recalculate the table and every plot for every 
				 * intermediate position of the column. So every new 
				 * intermediate column position triggers a delayed 
				 * recalculation which is cancelled when there is another
				 * column move during the next <delay> milliseconds.
				 */
				if (redrawRequest!=null) {
					redrawRequest.cancel();
				}
				redrawRequest = new java.util.Timer();
				redrawRequest.runAfter(delay) {
					ts.updateUpwardsFrom(ts.findByClass(SortedTable.class));
					updatePlots();
					plots.each { it.redraw() };
					jt2.repaint();
				};
			}));
			
			// Resizing is very awkward, this is the best way I could
			// do it. Took me 3 weeks to figure this out (20091013).
			def jsp = new JScrollPane(jt2);
			frame.addComponentListener(new ResizeListener({ 
				jsp.preferredSize=new Dimension(
						(int)swing.panel.size.width-margin, 
						(int)swing.panel.size.height-margin);
				jsp.revalidate();
				frame.validate();
			}));
			swing.panel.removeAll();
			swing.panel.add(jsp, BorderLayout.CENTER);
			frame.pack();
		});

		frame = swing.frame(id:'frame', title:"VdbenchExplorer",
				locationRelativeTo:null) {
			menuBar {
				menu("File") {
					menuItem(action:open)
					menuItem(action:exit)
				}
			}
			panel(id:'panel') {
				label("Open Vdbench flatfile.html first!");
			}
		}

		frame.pack();
		frame.show();
	}

	JPopupMenu createPopupMenuCell(int row, int col) {
		def fT = ts.findByName("RowFilter");
		def popup = swing.popupMenu(id:"popupcell") {
			menuItem() {
				action(name:"Only this value", closure: {
					fT.addFilter(col,
							jt2.model.getColumn(col).getRow(row).val, true);
					println "r="+row+" c="+col+" v="+jt2.model.getColumn(col).getRow(row).val;
					ts.updateUpwardsFrom(fT);
					jt2.repaint();
					jt2.tableHeader.repaint();
					updatePlots();
				})
			};
			menuItem() {
				action(name:"Exlude this value", closure: {
					fT.addFilter(col, 
							jt2.model.getColumn(col).getRow(row).val, false);
					println "r="+row+" c="+col+" v="+jt2.model.getColumn(col).getRow(row).val;
					ts.updateUpwardsFrom(fT);
					jt2.repaint();
					jt2.tableHeader.repaint();
					updatePlots();
				})
			};
		}
	}
	
	JPopupMenu createPopupMenu(int col) {
		def popup = swing.popupMenu(id:"popup") {
			menuItem() {
				def tag=jt2.model.getColumn(col).plotted?"Don't plot":'Plot';
				action(name:tag, closure: {
					jt2.model.getColumn(col).plotted=
						!jt2.model.getColumn(col).plotted;
					//println(jt2.model.getColumn(col).columnHead.description);
					updatePlots();
					/* Tried out many things to instantly redraw the column 
					 * headers: 
					 * jt2.revalidate(), jt2.repaint()
					 * 
					 * Both worked but only after another mouse click.
					 * The following works instantly.
					 */
					jt2.tableHeader.repaint();
				})
			};
			menuItem() {
				def tag=jt2.model.getColumn(col).groupby?
						"Don't Group By":'Group By';
				action(name:tag, closure: {
					// TODO: Allow larger cardinalities by creating ranges
					if ((jt2.model.getColumn(col).cardinality()>groupbylimit) &&
							(!jt2.model.getColumn(col).groupby)) {
						JOptionPane.showMessageDialog(frame, 
								"Columns' cardinality is too large ("+
								jt2.model.getColumn(col).cardinality()+">"+
								groupbylimit+")", "GroupBy not possible", 
								JOptionPane.INFORMATION_MESSAGE);
						return;
					}
					
					jt2.model.getColumn(col).groupby=
						!jt2.model.getColumn(col).groupby;
					
					// TODO: Allow more columns for grouping
					// Remove groupby for other columns
					0.upto(jt2.model.getColumnCount()-1) {
						if (it!=col) {
							jt2.model.getColumn(it).groupby=false;
						}
					}

					if (jt2.model.getColumn(col).groupby) {
						groupby=jt2.model.getColumn(col);
						plots.each { plot -> 
							plot.groupby(groupby);
						};
					} else {
						plots.each { plot -> 
							groupby=null;
							plot.groupby(null);
						}
					};

					/* Tried many things to instantly redraw the column 
					 * headers: 
					 * jt2.revalidate(), jt2.repaint()
					 * 
					 * Both worked but only after another mouse click.
					 * Repainting jt2's TableHeader works instantly:
					 */
					jt2.tableHeader.repaint();
				})
			};
			if (jt2.model.getColumn(col).filtered) {
				menuItem() {
					def fT = ts.findByName("RowFilter");
					action(name:"Remove row filters for this column", closure: {
						fT.removeColFilters(col);
						ts.updateUpwardsFrom(fT);
						jt2.repaint();
						jt2.tableHeader.repaint();
						updatePlots();
					});
				}
			}
		};		
	}

	void updatePlots() {
		int count=0;
		def xcol=-1;
		def ycols=[];

		// Determine the plottable columns
		// The first column that is checked as plot is always the x column
		0.upto(jt2.model.getColumnCount()-1) { col ->
			int tmp = jt2.convertColumnIndexToModel(col);
			if (jt2.tm.getColumn(tmp).plotted) {
				if (count==0) {
					xcol = jt2.model.getColumn(tmp);
					//println "xcol="+tmp+"="+xcol;
				} else {
					ycols << jt2.model.getColumn(tmp);
					//println "ycol="+tmp;
				}
				count++;
			}
		}

		// Remove all plots that are not needed anymore and put them onto
		// the reuse stack
		def plots2 = [];
		def reuse = [];
		//println "Before: "+plots;
		plots.each { plot ->
			//println "plot -> "+plot;
			if (plot.plotFrame == null) {
				return;
			}
			
			//println "plot.cx="+plot.cx+" xcol="+xcol;
			if (plot.cx!=xcol) {
				reuse << plot;
				return;
			}

			boolean found = false
			ycols.each { ycol ->
				if (ycol==plot.cy) {
					//println "plot.cy="+plot.cy+" ycol="+ycol;
					found = true;
					return;
				}
			}
			if (found) {
				plots2 << plot;
			} else {
				reuse << plot;
			}
		}
		plots = [];
		plots2.each { it -> plots << it };
		//println "After: "+plots;
		//println "Reuse: "+reuse;
		
		// Create all plots that are needed and that do not already exist
		ycols.each { ycol ->
			//println "ycol: "+ycol;
			//println "plots: "+plots.size+" reuse: "+reuse.size;

			boolean found = false;
			plots2.each { plot ->
				//println plot;
				if (plot.cy==ycol) {
					//println ycol.toString()+" found in "+plot;
					found = true;
					return;
				}
			}

			if (!found) {
				// Reuse plots from the reuse stack before creating new ones
				if (reuse.size>0) {
					//println "reuse plot";
					//println "adding "+xcol+" "+ycol;
					reuse[0].reinit(xcol, ycol);
					//println "cx, cy="+reuse[0].cx+" "+reuse[0].cy;
					plots << reuse[0];
					reuse.remove(0);
				} else {
					//println "create new";
					//println "adding "+xcol+" "+ycol;
					plots << new Plot(xcol, ycol, groupby);
				}
			}
		}
		//println "After2: "+plots;
		//println "Reuse2: "+reuse;

		reuse.each { plot ->
			plot.kill();
		}
		
	}
}

assert Column.guessType((String[])["1", "2", "3"]) == Type.INT;
assert Column.guessType((String[])["1.1", "2", "3e-5"]) == Type.FLOAT;
assert Column.guessType((String[])["1.a", "e2", "1.0eg7"]) == Type.LABEL;
assert Column.guessType((String[])["09:01:02.123", "23:57:58.987"]) == Type.TIME;

v = new VdbenchFlatfileTable("/Users/jf/BO/masterdata/XXX/flatfile.html");

assert v.getColumn(0).columnHead.name == "tod";
assert v.getColumn(1).columnHead.name == "Run";
assert v.getColumn(3).columnHead.name == "reqrate";
assert v.getColumn(5).columnHead.name == "MB/sec";

assert v.getColumn(0).columnType == Type.TIME;
assert v.getColumn(1).columnType == Type.LABEL;
assert v.getColumn(3).columnType == Type.FLOAT;
assert v.getColumn(5).columnType == Type.FLOAT;

assert v.getColumn(0).cardinality() == v.getColumn(0).length();
assert v.getColumn(3).cardinality() == 1;

0.upto(v.getColumnCount()-1) {
	col=v.getColumn(it);
	assert col.columnType == col.guessType();
	assert (1..col.length()).contains(col.cardinality()); 
}

t1 = new SimpleTable();
t1.add(new SimpleColumn("c1", "c1", (String[])["a","b","a","c","a","a"]));
t1.add(new SimpleColumn("c2", "c2", (String[])["b","b","a","d","b","d"]));
t1.add(new SimpleColumn("c3", "c3", (String[])["a","b","c","a","b","d"]));
t2 = new SortedTable(t1);
t2.sort();
assert t2.getValueAt(0,0) == "a";
assert t2.getValueAt(1,0) == "a";
assert t2.getValueAt(4,0) == "b";
assert t2.getValueAt(5,0) == "c";
assert t2.getValueAt(0,1) == "a";
assert t2.getValueAt(1,1) == "b";
assert t2.getValueAt(2,1) == "b";
assert t2.getValueAt(4,1) == "b";
assert t2.getValueAt(0,2) == "c";
assert t2.getValueAt(1,2) == "a";
assert t2.getValueAt(2,2) == "b";
assert t2.getValueAt(2,2) == "b";
assert t2.getValueAt(4,2) == "b";
t3 = new RowFilteredTable(t2);
t3.addFilter(0, "b", false);
t3.applyFilters();
assert t3.length()==5;
0.upto(t3.length()-1) {
	assert t3.getValueAt(it, 0) != "b";
}
assert t3.getValueAt(0,0) == "a";
assert t3.getValueAt(1,0) == "a";
assert t3.getValueAt(4,0) == "c";
assert t3.getValueAt(0,1) == "a";
assert t3.getValueAt(1,1) == "b";
assert t3.getValueAt(2,1) == "b";
assert t3.getValueAt(4,1) == "d";
assert t3.getValueAt(0,2) == "c";
assert t3.getValueAt(1,2) == "a";
assert t3.getValueAt(2,2) == "b";
assert t3.getValueAt(2,2) == "b";
assert t3.getValueAt(4,2) == "a";

t1 = new SimpleTable();
t1.add(new SimpleColumn("c1", "c1", Type.INT, (String[])["0","1","0","2","0","0"]));
t1.add(new SimpleColumn("c2", "c2", Type.INT, (String[])["1","1","0","3","1","3"]));
t1.add(new SimpleColumn("c3", "c3", Type.INT, (String[])["0","1","2","0","1","3"]));
ts = new TableStack();
ts.add(t1);
ts.add(ColumnFilteredTable.class);
t2=ts.findByClass(ColumnFilteredTable.class);
t2.addSyntheticColumn((Column[])[t1.getColumnByName("c1"), t1.getColumnByName("c2")], "col['c1']+col['c2']");
assert t2.getValueAt(3,0) == 2;
assert t2.getValueAt(4,1) == 1; 
assert t2.getValueAt(0,3) == 1;
assert t2.getValueAt(1,3) == 2;
assert t2.getValueAt(3,3) == 5;

v = new VdbenchExplorerGUI();

