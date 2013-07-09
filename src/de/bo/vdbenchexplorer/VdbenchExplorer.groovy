/* Plot tables
 * Jochen Fritzenk√∂tter, Baltic Online Computer GmbH, Kiel 2009-2013
 */

package de.bo.vdbenchexplorer;

import groovy.lang.GroovyShell;
import groovy.swing.SwingBuilder;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.regex.Matcher;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.plaf.UIResource;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;
import javax.swing.table.TableModel;
import javax.swing.event.MouseInputAdapter
import javax.swing.event.TableColumnModelEvent;
import javax.swing.event.TableColumnModelListener;
import javax.swing.event.TableModelEvent;
import javax.swing.event.TableModelListener;
import javax.swing.plaf.basic.BasicButtonListener;

import org.w3c.dom.ls.LSException;

import jplot.*;

enum Type { DATETIME, TIME, LABEL, INT, FLOAT };

/* TODO: unsolved bugs
 * - sometimes when moving columns an exception occurs:
 * Exception in thread "AWT-EventQueue-0" java.lang.NullPointerException: Cannot invoke method getRow() on null object
 * ...
 * at de.bo.vdbenchexplorer.ProxyColumn$getRow.call(Unknown Source)
 * at de.bo.vdbenchexplorer.Table.getValueAt(VdbenchExplorer.groovy:55)
 * - When adding or removing columns the column order is reset to the default.
 * This can be annoying, we need to save the column order somehow.
 */

/* All kinds of Tables
 */

abstract class Table extends AbstractTableModel {
	String name;
	String description;
	def cols=[];
	
	Table(String n, String d) {
		name=n;
		description=d;
	}
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
		if (cols[col].getRow(0).getTypedVal() != null) {
			return cols[col].getRow(0).getTypedVal().class;
		}
		return java.lang.String;
	}
	
	// Additional abstract methods
	
	abstract void update();
	
	// Own methods
	
	void add(Column c) {
		cols << c;
	}
	
	void add(Row r) {
		add(r.cells);
	}
	
	void add(Cell[] row) {
		def c = 0;
		row.each { cell ->
			cols[c++].add(cell);
		}
		//print();
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
	
	/* Returns an array with preconfigured column orders.
	 * This default implementation returns an empty hash.
	 * Have a look at the implementing classes.
	 */
	ArrayList preconf_order_and_plot() {
		return [];
	}
	
	void print() {
		cols.each {
			print it.columnHead.name+"\t";
		}
		println "";
		
		0.upto(getRowCount()-1) { r ->
			cols.each { c->
				print c.getRow(r).val+"\t";
			}
			println "";
		}
	}
	
}

class SimpleTable extends Table {
	
	SimpleTable() {
		super("", "");
	}
	
	SimpleTable(String n, String d) {
		super(n, d);
	}
	
	void update() {};
}

/* A table that is fully sorted and in that every row differs at least in 
 * one column from all other rows.
 */
class SortedUniqueTable extends Table {
		
	SortedUniqueTable(Table t) {
		this("", "", t);
	}

	SortedUniqueTable(String n, String d, Table t) {
		super(n, d);
		extract_unique_rows(t);
	}
	
	void update() {
		extract_unique_rows(t);
	};
	
	private void extract_unique_rows(Table t) {
		cols = [];
		0.upto(t.getColumnCount()-1) { it ->
			cols << new SimpleColumn(t.getColumn(it).getColumnHead()); 
		}
		if (t.getRowCount() > 0) {
			SortedTable sot = new SortedTable(t);
			//t.print();
			//sot.print();
			def row = sot.getRow(0);
			add(row);
			def r = 1;
			while (r < sot.getRowCount()) {
				//println r+" "+sot.getRowCount();
				def nrow = sot.getRow(r);
				if (Row.diff(row, nrow)) {
					row=nrow;
					add(row);
				}
				r++;
			}
		}
	}
		
}

class VdbenchFlatfileTable extends Table {
	def h=[:], heads;
	
	VdbenchFlatfileTable(File file) {
		super(file.toString(), file.toString());
		Matcher m;
		String[] ls = file.readLines();
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
					cols[col-1].add(new Cell(cols[col-1], row[col-1]));
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
	
	ArrayList preconf_order_and_plot() {
		return [
		        	['plot':['bytes/io', 'resp', 'MB/sec'], 'group':['Dataset'], 'rows':['threads=1','Run=sequential_write_max']],
		        	['order':['threads', 'rate', 'resp'], 'plot':['rate', 'resp'], 'group':['bytes/io','Dataset'], 'rows':['bytes/io=8192','Run=random_read_max']],
		        	['plot':['threads', 'MB/sec'], 'group':['bytes/io','Dataset'], 'rows':['bytes/io=262144','Run=sequential_read_max']],
        ];
	}
}

/* Matches a File with a header line with columns separated by white space
 * in the first row and data rows of the same column number in the following
 * lines 
 */
class AsciiFileTable extends Table {
	def h=[:], heads;
	def separator_candidates=[" \t", ",", ";"];
	
	AsciiFileTable(String file) {
		super(file, file);
		String[] ls = new File(file).readLines();
		//println ls.size();
		def t=this;
		def bc = best_candidate(ls);
		//println bc;
		heads=ls.first().tokenize(bc).toList();
		//println heads;
		heads.each {
			cols << new SimpleColumn(new ColumnHead(name:it, 
					description:it));
		}
		ls.tail().each {
			def row=it.tokenize(bc).toList();
			0.upto(row.size()-1) { col ->
				cols[col].add(new Cell(cols[col], row[col]));
			}
		}
		cols.each {
			it.columnType=it.guessType();
			it.initSymbols();
		}
	}
	
	// TODO: reinit AsciiFileTable
	void update() {};
	
	String[] boringColumns() {
		def list = [];
		return list;
	}
	
	private String best_candidate(list) {
		// Usually the header gives a hint
		def r=[:];
		separator_candidates.each { r[it] = list[0].split(it).size() }
		return (r.keySet().sort { a, b -> r[b] <=> r[a] })[0];
	}
}

/* Parse sar output data. This is a bit awkward, because sar -d outputs
 * a sub-table for every timestamp (i.e. for every disk a row). The best
 * way would perhaps be to build a separate disk table and join it with the 
 * rest of the sar table on the timestamp column. For now, we build one table
 * with every sar row replicated n times for the n disks.
 * The whole code is a terrible hack, I'm sorry.
 * */
class SarOutputTable extends Table {
	SarOutputTable(String file) {
		super(file, file);
		def line;
		def ls = new File(file).readLines().toList();
		ls.remove(0);
		def h1=ls.remove(0);
		ls.remove(0);
		
		// Parse headers
		def m = ls[0] =~ /^(\d{2,2}:\d{2,2}:\d{2,2})/;
		ls[0] = m.replaceFirst("");
		def heads=[];
		heads[0] = ["tod"];
		def count_outputs=1;
		while(! (ls[0] =~ /^\s*$/) && !(ls[0] =~ /^(\d{2,2}:\d{2,2}:\d{2,2})/)) {
			line = ls.remove(0);
			heads[count_outputs++] = line.split().toList();
		}
		while (ls[0] =~ /^\s*$/) {
			ls.remove(0);
		}
		
		// Parse data
		def countdevice=null;
		def rows=0;
		def data=[]
		while(ls.size()>0 && !(ls[0] =~ /^Average/)) {
			m = ls[0] =~ /^(\d{2,2}:\d{2,2}:\d{2,2})/;
			if (m) {
				data[rows] = [];
				data[rows][0] = [m[0][1]];
				ls[0] = m.replaceFirst("");
				def count = 1;
				while (!(ls[0] =~ /^\s*$/) && !(ls[0] =~ /^\d{2,2}:\d{2,2}:\d{2,2}/)) {
					def inside_device=false;
					if (heads[count][0]=="device") {
						inside_device=true;
						countdevice=count;
					}
					if (!inside_device) {
						data[rows][count] = ls.remove(0).split().toList().
							collect { (it =~ /\/.*$/).replaceAll("") }
					} else {
						data[rows][count]=[:];
						while (ls[0] =~ /^\s*(\S*[a-zA-Z]\S+)\s+/) {
							def s;
							s = ls.remove(0).split().toList();
							data[rows][count][s.head()] = s.tail();
						}
						inside_device=false;
					}
					count++;
				}
				rows++;
			}				
			while (ls[0] =~ /^\s*$/) {
				ls.remove(0);
			}
		}
		
		// Create columns
		heads.flatten().each {
			cols << new SimpleColumn(new ColumnHead(name:it,
					description:it));
		}
		data.each { row ->
			def rp1, rp2, rp;
			if (countdevice) {
				if (countdevice > 1) {
					rp1=row[0..countdevice-1].flatten();
				} else { 
					rp1=[];
				}
				if (countdevice < row.size-1) {
					rp2=row[countdevice+1..-1].flatten();
				} else {
					rp2=[];
				}
				row[countdevice].each { k,v ->
					rp=rp1+[k]+v+rp2;
					(0..(rp.size()-1)).each { col->
						cols[col].add(new Cell(cols[col], rp[col]));
						//println cols[col].length()+" "+col+"-> "+rp[col];
					}
				}
			} else {
				rp = row.flatten();
				(0..(rp.size()-1)).each { col ->
					cols[col].add(new Cell(cols[col], rp[col]));
				}
			}
			
			cols.each {
				it.columnType=it.guessType();
				it.initSymbols();
			}
			cols[0].columnHead.description="Time/s";
		}
	}
	
	// TODO: reinit AsciiFileTable
	void update() {};
	
	String[] boringColumns() {
		def list = [];
		return list;
	}
}

class SortedTable extends Table {
	Table masterTable;
	JTable2 jt2=null;
	RowMap rm;
	
	SortedTable(Table t) {
		this(t, null);
	}
	
	SortedTable(Table t, JTable2 jt2) {
		super(t.name, t.description);
		this.masterTable=t;
		this.jt2=jt2;
		rm = new RowMap();
		init();
		sort();
	}
	
	private void init() {
		def c;
		cols = [];
		rm.init(masterTable.cols[0].length());
		/*		println "this="+this+" c="+masterTable.getColumnCount()+
		 " r="+masterTable.getRowCount()+" m="+masterTable;
		 */		
		masterTable.cols.each { col ->
			c = new ProxyColumn(rm, col);
			cols << c;
		}
	}
	
	void update() {
		init();
		sort();
	}
	
	void setJTable2(JTable2 jt2) {
		this.jt2 = jt2;
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
		
		if (cols[0].length()==0) { 
			rm.real2virt = [];
			rm.virt2real = [];
			return;
		}
		
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
		if (jt2==null) {
			return col;
		} else {
			return jt2.convertColumnIndexToModel(col);
		}
	}
}

class RowFilteredTable extends Table {
	Table masterTable;
	JTable jt=null;
	RowMap rm;
	def closures=[];
	def descriptions=[:];
	def filters=[:];
	
	RowFilteredTable(Table t) {
		this(t, null);
	}
	
	RowFilteredTable(Table t, JTable jt) {
		super(t.name, t.description);
		this.masterTable=t;
		this.jt=jt;
		rm = new RowMap();
		init();
	}
	
	private void init() {
		def c;
		cols = [];
		rm.init(masterTable.cols[0].length());
		/*		println "this="+this+" c="+masterTable.getColumnCount()+
		 " r="+masterTable.getRowCount()+" m="+masterTable;
		 *//*		masterTable.cols.each {
		 println "n="+it.columnHead.name+" r="+it.length();
		 }
		 */		masterTable.cols.each { col ->
			c = new RowFilteredColumn(rm, col);
			cols << c;
			if (!filters[c.columnHead.name]) 
			{ filters[c.columnHead.name] = [] };
			c.filtered=(filters[c.columnHead.name].size()>0);
		}
		//println "v2r="+rm.virt2real+" r2v="+rm.real2virt;
	}
	
	void update() {
		init();
		applyFilters();
	}
	
	void addFilter(String cname, String[] vals, boolean inverse) {
		def c = { row ->
			def val = masterTable.getColumnByName(cname).getRow(row).val;
			boolean ret=vals.grep(val);
			ret=inverse?!ret:ret;
			//println "cname="+cname+" row="+row+" val="+val+" ret="+ret;
			return ret;
		};
		closures << c;
		descriptions[c] = cname.substring(0,Math.min(3,cname.size()))+(inverse?"=":"!=")+vals.join(",");
		if (!filters[cname]) { 
			filters[cname] = [];
		}
		filters[cname] << c;
		getColumnByName(cname).filtered = true;
		return;
	}
	
	void addFilter(String cname, String val, boolean inverse) {
		def c = { row ->
			boolean ret = 
					(masterTable.getColumnByName(cname).getRow(row).val == val);
			ret=inverse?!ret:ret;
			//println "cname="+cname+" row="+row+" val="+val+" ret="+ret;
			return ret;
		};
		closures << c
		descriptions[c] = cname.substring(0,Math.min(3,cname.size()))+(inverse?"=":"!=")+val;
		if (!filters[cname]) { 
			filters[cname] = [];
		}
		filters[cname] << c;
		getColumnByName(cname).filtered = true;
		return;
	}
	
	void removeAllFilters() {
		closures = [];
		descriptions = [:];
		filters = [:];
		cols.each { it.filtered = false }
	}
	
	void removeColFilters(String name) {
		filters[name].each { closures.remove(it); descriptions.remove(it); };
		filters[name] = [];
		getColumnByName(name).filtered=false;
	}
	
	void removeColFilters(int col) {
		removeColFilters(cols[col].columnHead.name);
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
		//println "v2r="+rm.virt2real+" r2v="+rm.real2virt;
	}
	
	int length() {
		return rm.virt2real.size();
	}
	
	String currentFilterDescription() {
		if (descriptions.size()) {
			descriptions.values().join(" ");
		} else {
			"";
		}
	}
}

class ColumnFilteredTable extends Table {
	Table masterTable;
	def removed = [];
	def synthetic = [];
	
	ColumnFilteredTable(Table t) {
		super(t.name, t.description);
		this.masterTable = t;
		cols = masterTable.cols;
		init();
	}
	
	private void init() {
		def c1;
		def c2 = cols;
		cols = [];
		/*		println "this="+this+" c="+masterTable.getColumnCount()+
		 " r="+masterTable.getRowCount()+" m="+masterTable;
		 */		masterTable.cols.each { col ->
			c1 = c2.find { it.columnHead.name == col.columnHead.name }
			def b = removed.grep { it.columnHead.name == col.columnHead.name }
			if (!c1 && !b) {
				cols << col;
			}
		};
		c2.each { col ->
			c1 = masterTable.cols.find { 
				it.columnHead.name == col.columnHead.name 
			};
			if (c1) { cols << c1 } else {
				c1 = synthetic.find {
					it.columnHead.name == col.columnHead.name	
				}
				if (c1) { cols << c1 }
			}			
		};	
	}
	
	// Implement abstract method from Table
	
	void update() {
		cols.each {
			if (it instanceof SyntheticColumn) {
				/* When the columns contents changed, an update is
				 * sufficient. If we have new columns, than we replace
				 * the base columns in the SyntheticColumn.
				 */
				
				if ((columnsFromExpression(it.formula).toList() -
				it.baseColumns.toList()).size()==0) {
					it.update();
				} else {
					//println "cols="+cols+" s="+synthetic+" n="+it.columnHead.name+" t="+it;
					it.replaceBaseColumns(columnsFromExpression(it.formula));
				}
			}
		}
		init();
	}
	
	// Own methods
	void addSyntheticColumn(String expr) {
		addSyntheticColumn(columnsFromExpression(expr), expr);
	}
	
	void addSyntheticColumn(Column[] baseCols, String expr) {
		def c = new SyntheticColumn(baseCols, expr);
		synthetic << c;
		cols << c;
	}
	
	private Column[] columnsFromExpression(String expr) {
		def c = [];
		expr.eachMatch(/\'(.*?)\'/) { match ->
			c << masterTable.getColumnByName(match[1]); 
		};
		return (Column[])c;
	}
	
	void removeColumn(Column col) {
		//println "remove "+col.columnHead.name;
		def realcol = cols.find { it.columnHead.name == col.columnHead.name }
		removed << realcol;
		cols.remove(realcol);
		synthetic.remove(realcol);
	}
	
	void removeColumnsByNames(String[] names) {
		def l;
		names.each { name ->
			l = cols.findAll { it.columnHead.name == name };
			l.each { removeColumn(it) }
		}
	}
	
	void removeColumn(int col) {
		removeColumn(cols[col]);
	}
}

class MergedTable extends Table {
	def masterTables;
	
	MergedTable(Table t) {
		super(t.name, t.description);
		masterTables=[t];
		init();
	}
	
	private void init() {		
		def c1;
		def c2;
		def n;
		/*
		println "this="+this
		masterTables.each { 
			println "  c="+it.columnCount+" r="+it.rowCount+" m="+it;
		}
		*/	
		c1 = [];
		n=0;
		masterTables.each { t ->
			t.cols.each {
				if (!c1.contains(it.columnHead.name)) {
					c1 << it.columnHead.name;
				}
			};
			n += t.rowCount;
		}
		//println "c1="+c1.size()+" n="+n;
		
		
		cols = [];
		if (masterTables.size()>1) {
			def shortNames = makeShortNames(masterTables*.name);
			c2 = new ConcatColumn("Dataset", "Dataset");
			c2.columnType = Type.LABEL;
			masterTables.each { t ->
				//println shortNames[t.name]+" "+t.rowCount;
				c2.add(t, new ConstColumn(shortNames[t.name], t.rowCount));
			}
			cols << c2;
		}
		
		c1.each { c ->
			def ccc;
			if (masterTables[0].getColumnByName(c) != null) { 
				ccc = new ConcatColumn(c, masterTables[0].
						getColumnByName(c).columnHead.description);
				masterTables.each { t ->
					def col = t.cols.
							find { it.columnHead.name == c };
					if (col) {
						ccc.add(t, col);
					} else {
						ccc.add(t, new ConstColumn(null, t.rowCount));
					}
				}
				cols << ccc;
			}
		}
	}
	
	void add(Table t) {
		masterTables << t;
		init();
	}
	
	void remove(Table t) {
		masterTables.remove(t);
		init();
	}
	void update() {}
	
	Table[] listTables() {
		return masterTables;
	}
	
	/* Make short unique substrings from otherwise probably long but largely 
	 * identical file names
	 */
	static HashMap makeShortNames(names) {
		def hm = [:];
		int mp = 0;
		int ms = 0;
		int l;
		//println names;
		if (names.size()==0) {
			return hm;
		}
		String wp = names[0];
		if (names.size()==1) {
			hm[wp] = "~";
			return hm;
		} 
		String ws = names[0].reverse();
		l = wp.length();
		ms = l;
		mp = l;
		int count=1;
		int i;
		def s;
		//println wp+"<->"+ws;
		while (count<names.size()) {
			i = 0;
			s = names[count];
			while(i<mp && i<s.length() && s[i]==wp[i]) { i++ };
			mp = i;
			i = 0;
			s = s.reverse();
			while(i<ms && i<s.length() && s[i]==ws[i]) { i++ };
			ms = i;
			//println count+" "+names[count]+" "+mp+" "+ms;
			//println names[count].substring(0, mp)+" "+wp.substring(0, mp);
			//println names[count].reverse().substring(0, ms)+" "+ws.substring(0,ms);
			count++;
		}
		names.each {
			if (mp+ms>=it.length()) {
				s = "";
			} else {
				s = it.substring(mp,it.length()-ms);
			}
			if (mp>0) { s="~"+s; }
			if (ms>0) { s=s+"~"; }
			if (s == "~~") { s="~"; }
			hm[it] = s;
		}
		//println hm;
		return hm;
	}
	
}

/* All kinds of columns
 */

abstract class Column {
	private Type columnType = Type.LABEL;
	ColumnHead columnHead;
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
		} else if (l.grep(~/\d{1,2}:\d{2}:\d{2}/).size()==l.size()) {
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
			add(new Cell(this, it));
		}
		return this;
	}
	
	void print() {
		println getVals().join(", ");
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
			cells << new Cell(this, it);
		}
	}
	
	SimpleColumn(String head, String desc, Type type, String[] vals) {
		this.columnHead = new ColumnHead(name:head, description:desc);
		this.columnType = type;
		vals.each {
			cells << new Cell(this, it);
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
	
	// We assign artificial float numbers to labels in order to be able
	// to plot them
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
		if (length()>0) {
			0.upto(length()-1) {
				dv << dr[rm.virt2real(it)];
			}
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
	private Column[] baseColumns;
	private String formula;
	private Binding b;
	private GroovyShell gs;
	
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
		def vals;
		def expr = formula.replaceAll(/\'(.*?)\'/, /col['$1'][it]/);
		def expr2 = "(0.."+(baseColumns[0].length()-1)+
				").collect { $expr }";
		//println formula+" - "+expr+" - "+expr2;
		cells = [];
		b.col=[:];
		baseColumns.each { c ->
			b.col[c.columnHead.name]=[];
			0.upto(c.length()-1) { r -> 
				b.col[c.columnHead.name][r]=c.getRow(r).getTypedVal();
			}
		}
		//println "b="+b.col;
		/* GroovyShell does the hard work for us. Since GroovyShell
		 * allows to excute any kind of code, this could be dangerous.
		 * But nobody can force the user to enter harmful expressions, 
		 * so this is safe. And harmful typos are very improbable.
		 */
		vals = gs.evaluate(expr2)*.toString();
		//println "v="+vals.size()+" b="+baseColumns[0].length();
		vals.each {
			cells << new Cell(this, it);
		}
		columnType=this.guessType();
	}
	
	void update() {
		init();
	}
	
	void replaceBaseColumns(Column[] cols) {
		this.baseColumns = cols;
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
	
	boolean isFiltered() {
		// Since this is a new column, it is never row filtered. 
		return false;
	}
	
	Column[] getBaseColumns() {
		return baseColumns;
	}
	
	String getFormula() {
		return formula;
	}
	
}

class ConcatColumn extends SimpleColumn {
	def rowMap = [:];
	def tables = [];
	def cols = [:];
	int length = 0;
	
	ConcatColumn(String name, String desc) {
		super(new ColumnHead(name:name, description:desc));
	}
	
	// Own methods
	
	void add(Table t, Column c) {
		def ls = length;
		length += t.rowCount;
		tables << t;
		cols[t] = c;
		rowMap[t]=(ls..(length-1));
		c.vals.each {
			cells << new Cell(this, it);
		}
		columnType = guessType((String[])this.cells*.val);
		//println "t="+tables+" cls="+cols+" rM="+rowMap;
	}
	
	void remove(Table t) {
		cols.remove(t);
		tables.remove(t);
		rowMap[t].each { cells.remove(it) }
		rowMap.remove(t);
		columnType = guessType((String[])this.cells*.val);
	}
	
	void update() {
		cells = [];
		tables.each {
			cols[t].vals.each {
				cells << new Cell(this, it);				
			}
		}
		columnType = guessType((String[])this.cells*.val);
	}
}

class ConstColumn extends Column {
	int length;
	Cell dummycell;
	
	ConstColumn(String val, int n) {
		length = n;
		dummycell = new Cell(this, val);
		columnType = guessType((String[])[dummycell.val]);
	}
	
	Cell getRow(int row) { return dummycell }
	String[] getVals() { (String[]) (1..length).collect { dummycell.val } }
	void setRow(int row, Cell c) {}
	void add(Cell c) {length++}
	void removeAll() {length=0}
	int length() { return length }
	int cardinality() { return 1 }
	String[] distinctVals() { return (String[])[dummycell.val] }
	double[] getDoubles() { 
		def x = dummycell.getTypedVal();
		double t;
		
		if (columnType == Type.TIME) {
			t = x/1000;
		} else if (columnType == Type.LABEL) {
			t = l2d[x];
		} else {
			t = (double) x;
		}
		if (length>0) {
			return (double[]) (1..length).collect { t };
		} else {
			return (double[])[];
		}
	}
}

/* Helper classes
 */

class RowMap {
	def real2virt = [];
	def virt2real = [];
	
	RowMap() {}
	
	RowMap(int n) {
		init(n);
	}
	
	void init(int n) {
		if (n>0) {
			real2virt = 0..(n-1);
			virt2real = 0..(n-1);
		} else {
			real2virt = [];
			virt2real = [];
		}
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

class Row {
	Cell[] cells;
	
	Row(Cell[] cells) {
		this.cells = cells;
	}
	
	static boolean diff(Cell[] r1, Cell[] r2) {
		assert r1.size()==r2.size();
		for (it in 0..(r1.size()-1)) { 
			//println r1[it].val+" "+r2[it].val;
			if (r1[it].val != r2[it].val) {
				return true;
			}
		}
		return false;
	}
}

class Cell {
	Column column;
	String val;
	
	Cell(Column c, String val) {
		this.column = c;
		this.val = val;	
	}
	
	Type getType() {
		return column.columnType;
	}
	
	Object getTypedVal() {
		if (val == null) { return null }
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
				} else if (val =~ /\d{1,2}:\d{2}:\d{2}/) {
					return Date.parse("HH:mm:ss", val);
				}
				return (Date)val;
				break;
		}
	}
}

/* The graphics
 */

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
	Column[] groupby = null;
	String description = null;
	Graph_2D graph;
	GraphSettings gs;
	Dimension box;
	private VdbenchExplorerGUI vegui = null;
	private fixed = false;
	private show_line = true;
	private double line_x0, line_y0, line_x1, line_y1;
	private double[] x, y;
	
	private final def swing;
	private final def saveDialog;
	private def popup;
	
	private Plot() {
		plotFrame = new JFrame();
		p = new JPanel(new BorderLayout());
		plotFrame.getContentPane().add(p);
		gs = new GraphSettings();
		swing = new SwingBuilder();
		saveDialog  = swing.fileChooser(
				dialogTitle:"Save PNG", 
				id:"saveDialog", acceptAllFileFilterUsed:true,
				fileSelectionMode: JFileChooser.FILES_ONLY) {};  		
	}	
	
	Plot(Column c1, Column c2, VdbenchExplorerGUI vegui) {
		this(c1, c2, vegui, null);
	}	
	
	Plot(Column c1, Column c2, VdbenchExplorerGUI vegui, Column[] g) {
		this();
		this.vegui = vegui;
		this.groupby = g;
		reinit(c1, c2);
	}	
	
	void reinit(Column c1, Column c2) {
		assert c1.length() == c2.length();
		this.cx = c1;
		this.cy = c2;
		x = cx.getDoubles();
		y = cy.getDoubles();
		this.line_x0 = x.toList().min();
		this.line_y0 = y.toList().min();
		this.line_x1 = x.toList().max();
		this.line_y1 = y.toList().max();
		init();
	}
	
	void reinit(Column c1, Column c2, Column[] g) {
		this.groupby = g;
		reinit(c1, c2);
	}
	
	void groupby(Column[] g) {
		this.groupby = g;
		init();
	}
	
	void redraw() {
		init();
	}
	
	private double toDataX(double pixelX) {
		/* graph.*Margin are different from gs.*Margin because they already 
		 * contain the distance to the axis. 
		 */  
		def axis = gs.panelSize.width-graph.leftMargin-graph.rightMargin;
		def diff = gs.getMaxValue(GraphSettings.X_AXIS)-gs.getMinValue(GraphSettings.X_AXIS);
		//println "w="+gs.panelSize.width+" axis="+axis+" diff="+diff+" lM="+graph.leftMargin+" rM="+graph.rightMargin;
		return (pixelX-graph.leftMargin)*diff/axis;
	}
	
	private double toDataY(double pixelY) {
		/* graph.*Margin are different from gs.*Margin because they already 
		 * contain the distance to the axis. 
		 */  
		def axis = gs.panelSize.height-graph.topMargin-graph.bottomMargin;
		def diff = gs.getMaxValue(GraphSettings.Y_AXIS)-gs.getMinValue(GraphSettings.Y_AXIS);
		//println "h="+gs.panelSize.height+" axis="+axis+" diff="+diff+" tM="+graph.topMargin+" bM="+graph.bottomMargin;
		return (gs.panelSize.height-pixelY-graph.topMargin)*diff/axis;		
	}
	
	private DataArray create_line() {
		def slope;
		if (line_x1-line_x0==0.0f) {
			slope="inf";
		} else {
			slope=sprintf("%10.6g", ((line_y1-line_y0)/(line_x1-line_x0)));
		}
		
		/* Apparently there is no way to set the position of a 
		 * a random GraphLabel in pixel coordinates? For data coordinats
		 * there is the GraphLabel.DATA constant, but the OTHER constant
		 * does not evaluate the location at all. Using DATA and setLocation()
		 * together leads to unexpected results.
		 */
		
		GraphLabel gl;
		def dx=line_x1-line_x0;
		def dy=line_y1-line_y0;
		def dy2=y.toList().max()-y.toList().min();
		gl = new GraphLabel(GraphLabel.DATA, "dx="+sprintf("%10.6g", dx));
		gl.setDataLocation(x.toList().max()*0.9f, y.toList().min()+0.1f*dy2);
		gs.addLabel(gl);
		
		gl = new GraphLabel(GraphLabel.DATA, "dy="+sprintf("%10.6g", dy));
		gl.setDataLocation(x.toList().max()*0.9f, y.toList().min()+0.2f*dy2);
		gs.addLabel(gl);
		
		gl = new GraphLabel(GraphLabel.DATA, "slope="+slope);
		gl.setDataLocation(x.toList().max()*0.9f, y.toList().min()+0.3f*dy2);
		gs.addLabel(gl);
		
		DataArray ds = new DataArray();
		ds.addPoint(line_x0, line_y0);
		ds.addPoint(line_x1, line_y1);
		ds.drawSymbol=true;
		ds.symbol=5;
		ds.drawLegend=false;
		ds.dashLength=0.5f;
		ds.color=java.awt.Color.BLACK;
		
		return ds;
	}
	
	private void init() {
		if (x == null || x == [] || y == null || y == []) {
			kill();
			return;
		}		
		
		p.removeAll();
		
		//GraphSettings gs = new GraphSettings();
		gs.reset();
		gs.setMinValue(GraphSettings.X_AXIS, x.toList().min());
		gs.setMaxValue(GraphSettings.X_AXIS, x.toList().max());
		gs.setMinValue(GraphSettings.Y_AXIS, y.toList().min());
		gs.setMaxValue(GraphSettings.Y_AXIS, y.toList().max());
		if (fixed) { 
			gs.backgroundColor=java.awt.Color.LIGHT_GRAY; 
		} else {
			gs.backgroundColor=java.awt.Color.WHITE; 			
		}
		
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
		plotFrame.addWindowListener(new WindowAdapter2({
			kill();
			if (!fixed) { cy.plotted=false; }
			vegui.updatePlots();
			vegui.repaintTableHeader();
		}));
		
		def da = [];
		if (groupby!=null && groupby.size()>0) {
			SimpleTable st = new SimpleTable();
			groupby.each { g->
				 st.add(g);
			};
			SortedUniqueTable sut = new SortedUniqueTable(st);
			0.upto(sut.getRowCount()-1) { ncell ->
				def points = [];
				def cells1 = sut.getRow(ncell);
				DataArray d = new DataArray();
				//println "groupby="+groupby.getVals();
				0.upto(cx.length()-1) {
					def cells2 = st.getRow(it);
					if (!Row.diff(cells1, cells2)) {
						//println it+" "+x[it]+" "+y[it]+" "+" "+groupby.getRow(it).val+" "+val;
						points << [x[it], y[it]];
					}
				}
				//points = points.sort { it[0] };
				points.each { d.addPoint(it[0], it[1]) };
				d.setDrawSymbol(true);
				d.setSymbol(2);
				d.setColor(gencolor(ncell, sut.getRowCount()));
				d.name=cells1.collect { cell ->
					 cell.column.columnHead.name+"="+cell.val
				}.join(", "); 
				d.drawLegend=true;
				da << d;
				
				// Mark begin of line
				DataArray ds = new DataArray();
				ds.addPoint(points[0][0], points[0][1]);
				ds.setDrawSymbol(true);
				ds.setSymbol(2);
				ds.setColor(gencolor(ncell, sut.getRowCount()));
				ds.setSymbolSize((float)2*ds.getSymbolSize());
				ds.drawLegend=false;
				da << ds;
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
			
			// Mark begin of line
			DataArray ds = new DataArray();
			ds.addPoint(points[0][0], points[0][1]);
			ds.setDrawSymbol(true);
			ds.setSymbol(2);
			ds.setSymbolSize((float)2*ds.getSymbolSize());
			ds.drawLegend=false;
			da << ds;
		}
		
		if (show_line) {
			def ds = create_line();
			da << ds;
		}
		
		if (!box) {
			box = new Dimension(400,300);
		} else {
			box = p.getSize();
		}	
		gs.panelSize=box;

		Vector v = new Vector();
		da.each { it -> v.add(it) };
		
		graph = new Graph_2D(gs);
		graph.show(v);
		def filterDescription = vegui.getTableStack().findByName("RowFilter").currentFilterDescription();
		
		popup = swing.popupMenu(id:"popupplot") {
			menuItem() {
				action(name:"Save as PNG", closure: {
					def pic = graph.getBufferedImage(p.getSize());
					def file = plotFrame.title+" "+filterDescription;
					def f = (file =~ /\//).replaceAll("_");
					saveDialog.setSelectedFile(new File(f+".png"));		
					if (saveDialog.showSaveDialog() != JFileChooser.APPROVE_OPTION) { 
						return;
					}			
					ImageIO.write(pic, "png", new File(saveDialog.selectedFile.path));
				})
			}
			menuItem() {
				action(name:"Fix plot", closure: {
					vegui.fix_plot(this);
					fixed = true;
					redraw();
				})
			}
		};	

		graph.addMouseListener(new PopupListener({
			popup.show(it.getComponent(), it.getX(), it.getY());
		}));
		PlotMouseInputAdapter pmia = new PlotMouseInputAdapter(
			{
				println "p"+it;
				show_line = false;
				def yh=(it.getLocationOnScreen().y+it.y)/2;
				line_x0 = toDataX(it.getLocationOnScreen().x);
				line_y0 = toDataY(yh);
				println "("+it.getLocationOnScreen().x+","+yh+")"+"->("+line_x0+","+line_y0+")";
			},
			{
				println "d"+it;
				show_line = true;
				def yh=(it.getLocationOnScreen().y+it.y)/2;
				line_x1 = toDataX(it.x);
				line_y1 = toDataY(yh);
				println "("+line_x1+","+line_y1+")";
				//redraw();
			},
			{
				println "r"+it;
				def yh=(it.getLocationOnScreen().y+it.y)/2;
				line_x1 = toDataX(it.x);
				line_y1 = toDataY(yh);
				println "("+it.getLocationOnScreen().x+","+yh+")"+"->("+line_x1+","+line_y1+")";
				redraw();
			}
		);
		graph.addMouseListener(pmia);
		graph.addMouseMotionListener(pmia);
		p.setPreferredSize(box);
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
		float f = (float)((i+1)/(n+2));
		return new Color(Color.HSBtoRGB(f, (float)1.0, (float)1.0));
		/*		return new Color((float)(i+1)/(n+2), (float)(i+1)/(n+2), 
		 (float)(i+1)/(n+2));
		 */	}
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

class WindowAdapter2 extends WindowAdapter {
	Closure closure;
	
	WindowAdapter2(Closure c) {
		closure=c;
	}
	
	void windowClosing(WindowEvent e) {
		closure(e);
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

class PlotMouseInputAdapter extends MouseInputAdapter {
	Closure cp, cd, cr;
	
	PlotMouseInputAdapter(Closure cp, Closure cd, Closure cr) {
		this.cp = cp;
		this.cd = cd;
		this.cr = cr;
	}
	
	public void mousePressed(MouseEvent me) {
		cp(me);
	}
	
	public void mouseDragged(MouseEvent me) {
		cd(me);
	}
	
	public void mouseReleased(MouseEvent me) {
		cr(me);
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

class OrderAndPlotPresetButtonListener implements ActionListener {
	HashMap hm;
	TableStack ts;
	VdbenchExplorerGUI gui;
	
	OrderAndPlotPresetButtonListener(TableStack ts, HashMap hm, VdbenchExplorerGUI gui) {
		this.hm = hm;
		this.ts = ts;
		this.gui = gui;
	}
	
	synchronized void actionPerformed(ActionEvent e) {
		Table sorter = ts.findByName('Sorter');
		Table rowfilter = ts.findByName('RowFilter');
		JTable2 jt2 = sorter.jt2;
		// Unset plotted and groupby for all columns
		for(int i=0; i<jt2.model.columnCount; i++) {
			jt2.model.getColumn(i).plotted=false;
			jt2.model.getColumn(i).groupby=false;
		}
		/* Order the columns according to the "order" array or, if not
		 * existing, according to the "plot" array.
		 */		
		int npos = 0;
		(hm['order']?hm['order']:hm['plot']).each {
			int col = sorter.findColumn(it);
			if (col>=0) {
				def opos = jt2.convertColumnIndexToView(col);
				jt2.moveColumn(opos, npos++);
			}
		}
		/* Mark the columns to be grouped by
		 */
		hm['group'].each {
			int col = sorter.findColumn(it);
			if (col>=0) {
				jt2.model.getColumn(col).groupby=true;
			}
		}
		/* Mark the columns to be plotted and make sure they are
		 * not grouped by.
		 */
		hm['plot'].each {
			int col = sorter.findColumn(it);
			if (col>=0) {
				jt2.model.getColumn(col).plotted=true;
				jt2.model.getColumn(col).groupby=false;
			}
		}
		/*
		 * Remove row filters on ordered or plotted columns
		 * or columns that are going to be row filtered
		 */
		(hm['plot']+hm['order']+
				(hm['rows'].collect {it.replaceAll(/=.*$/,"")})
				-null).unique().each {
			int col = sorter.findColumn(it);
			if (col>=0) {
				rowfilter.removeColFilters(it);
			}
		}
		/* Preconfigured row filters
		 */
		hm['rows'].each {
			def matches = (it =~ /^(\S+)=(\S+)$/);
			if (matches) {
				def cname = matches[0][1];
				def val = matches[0][2];
				//println cname+" "+val;
				int col = sorter.findColumn(cname);
				if (col >= 0) {
					rowfilter.addFilter(cname, val, true);
				}
			}
		}
		ts.updateUpwardsFrom(rowfilter);
		jt2.model.fireTableDataChanged();
		gui.updatePlots();
		jt2.tableHeader.repaint();			
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

public class DateCellRenderer extends DefaultTableCellRenderer{
	public Component getTableCellRendererComponent(JTable table, Object value, boolean
	isSelected, boolean hasFocus, int row, int column){
		super.getTableCellRendererComponent( table, value, isSelected, hasFocus, row, column );
		if ( value instanceof Date ){
			// Use SimpleDateFormat class to get a formatted String from Date object.
			String strDate = new SimpleDateFormat("HH:MM:ss.SSS").format((Date)value);
			// Sorting algorithm will work with model value. So you dont need to worry
			// about the renderer's display value.
			this.setText( strDate );
		}

		return this;
	}
}
	
/* End of Renderers
 * 
 */

class JTable2 extends JTable {
	private int margin = 5;
	private boolean ready_for_init=false;
	private CustomHeaderRenderer tcr;
	private DateCellRenderer dcr;
	
	JTable2(TableModel tm) {
		super(tm);
		tcr = new CustomHeaderRenderer();
		dcr = new DateCellRenderer();
		ready_for_init=true;
		init();
	}
	
	private void init() {
		if (!ready_for_init) { return; }
		
		/* Prohibits that all columns are squeezed into one window.
		 * Resizing all columns afterwards is way too awkward, so having
		 * a scrollbar is much better.
		 */
		this.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
		
		0.upto(this.model.columnCount-1) { col ->
			if (col < this.columnModel.columnCount) {
				this.columnModel.getColumn(col).preferredWidth=
						this.columnWidth(col);
				this.columnModel.getColumn(col).headerRenderer=tcr;
				if (this.model.getColumnClass(col) == java.util.Date) {
					if (this.model.getColumn(col).columnType == Type.TIME) {
						this.columnModel.getColumn(col).setCellRenderer(dcr);
					}
				}
			}
		}
	}
	
	void reinit() {
		init();
	}
	
	int columnWidth(int col) {
		int w = 0;
		def tc = this.columnModel.getColumn(col);
		
		// TODO: What is the equivalent of prepareRenderer for the header?
		// new JTableHeader(tcm).getHeaderRect(col) ?
		def r = tc.headerRenderer;
		if (tc.headerRenderer == null) {
			r = this.tableHeader.defaultRenderer;
		}
		w = Math.max(w, r.preferredSize.width);
		
		0.upto(this.model.rowCount-1) { row ->
			def tcr2 = this.getCellRenderer(row, col);
			this.prepareRenderer(tcr2, row, col);
			def wc = tcr2.preferredSize.width;
			w = Math.max(w, wc);
		}
		
		return w+2*margin;
	}
}

class SimpleFileFilter extends javax.swing.filechooser.FileFilter {
	private String classFilter;
	private static supported_list = [
	                                 "ASCII table":AsciiFileTable.class,
	                                 "SAR output":SarOutputTable.class,
	                                 "Vdbench flatfile":VdbenchFlatfileTable.class,
	];
	
	SimpleFileFilter(String cl) {
		classFilter = cl;
	}
	
	boolean accept(File f) {
		if (f.directory) {
			return true;
		}
		
		if (supported_list[classFilter] == VdbenchFlatfileTable.class) {
			if (f.name == "flatfile.html") {
				return true;
			} else {
				return false;
			}
		} else if (supported_list[classFilter] == AsciiFileTable.class) {
			return true;
		} else if (supported_list[classFilter] == SarOutputTable.class) {
			return true;
		}
	}
	
	String getDescription() {
		return classFilter;
	}
	
	Class getTableClass() {
		return supported_list[classFilter];
	}
	
	static String[] supported() { return supported_list.keySet() }
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
	def frame;
	def plots = [];
	def fixedplots = [];
	private JTable2 jt2;
	private Column[] groupby = null;
	private java.util.Timer redrawRequest = null;
	private TableStack ts;
	
	private final int margin = 10;
	private final int groupbylimit = 100;
	private final int delay = 500;
	private final int preset_height = 100;
	
	private final def swing;
	private final def exit;
	private final def open;
	private final def addf;
	private final def remf;
	private final def openDialog;
	private final def about;
	private final def syntheticcolumn;
	private final def removecolumn;
	private final def menubar1;
	private final def menubar2;
	
	public VdbenchExplorerGUI() {
		swing = new SwingBuilder();
		
		openDialog  = swing.fileChooser(
				dialogTitle:"Choose a table file", 
				id:"openDialog", acceptAllFileFilterUsed:false,
				fileSelectionMode: JFileChooser.FILES_AND_DIRECTORIES) {};                
		SimpleFileFilter.supported().each { 
			openDialog.addChoosableFileFilter(
				new SimpleFileFilter(it));
		}
		openDialog.multiSelectionEnabled=true;
			
		exit = swing.action(name:'Exit', closure:{System.exit(0)});
		addf = swing.action(name:'Add Table', closure:{
			if (openDialog.showOpenDialog() != JFileChooser.APPROVE_OPTION) 
				return;
			
			def tm = ts.findByName("Merger");
			def file_list = [];
			openDialog.selectedFiles.each {
				//println it.toString();
				if (it.isDirectory()) {
					file_list << find_files(it, openDialog.fileFilter);
				} else {
					file_list << it;
				}
			}
			file_list = file_list.flatten();
			//println file_list;
			
			if (file_list.size()==0) {
				return;
			}
			
			file_list.each {
				def ta = openDialog.fileFilter.tableClass.newInstance(it);
				tm.add(ta);				
			}			

			ts.updateUpwardsFrom(tm);

			/* Hack: currently, with fireTableStructureChanged()
			 * we lose the column order. So we only fire the event
			 * when absolutely necessary, i.e. when we add the  
			 * Dataset column, i.e. when we add the second
			 * table.
			 */	            
			def tl = tm.listTables();
			if (tl.size()==2) {
				jt2.model.fireTableStructureChanged();
			} else {
				jt2.model.fireTableDataChanged();            	
			}
			jt2.reinit();
			updatePlots();
		});
		remf = swing.action(name:'Remove Table', closure:{
			def t = ts.findByName("Merger");
			def tl = t.listTables();
			def ret = JOptionPane.showInputDialog(null, 
					"Choose a table to remove", 
					"Remove Table", JOptionPane.QUESTION_MESSAGE, 
					null, (Object[]) tl*.name, null);
			if (!ret) return;
			
			def t1=tl.find { it.name == ret };
			if (tl.size()>1) {
				t.remove(t1);
				ts.updateUpwardsFrom(t);
				/* Hack: currently, with fireTableStructureChanged()
				 * we lose the column order. So we only fire the event
				 * when absolutely necessary, i.e. when we lose the 
				 * Dataset column, i.e. when we remove the last but one
				 * table.
				 */	            
				if (tl.size()==2) {
					jt2.model.fireTableStructureChanged();
				} else {
					jt2.model.fireTableDataChanged();
				}
				jt2.reinit();
				updatePlots();
			} else {
				init();
			}
		});
		open = swing.action(name:'Open', closure:{			
			if (openDialog.showOpenDialog() != JFileChooser.APPROVE_OPTION) 
				return;
			
			def file_list = [];
			openDialog.selectedFiles.each {
				//println it;
				if (it.isDirectory()) {
					file_list << find_files(it, openDialog.fileFilter);
				} else {
					file_list << it;
				}
			}
			file_list=file_list.flatten();
			//println file_list;
				
			if (file_list.size()==0) {
				return;
			}
			
			def file = file_list.pop();
			def t1 = openDialog.fileFilter.tableClass.newInstance(file);
			
			ts.add(t1, "Base");
			ts.add(MergedTable.class, "Merger");
			def tm = ts.findByName("Merger");

			file_list.each {
				def ta = openDialog.fileFilter.tableClass.newInstance(it);
				tm.add(ta);				
			}			
			ts.updateUpwardsFrom(tm);
			
			ts.add(ColumnFilteredTable.class, "Synthetic");
			ts.findByName("Synthetic").
					removeColumnsByNames(ts.findByName("Base").boringColumns());
			ts.add(RowFilteredTable.class, "RowFilter");
			ts.add(SortedTable.class, "Sorter");
			
			jt2 = new JTable2(ts.top());
			
			// The "Sorter" needs to know the current column order
			ts.findByName("Sorter").setJTable2(jt2);
			
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
				 * recalculation which is canceled when there is another
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
			
			def n_order=ts.findByName("Base").preconf_order_and_plot().size();
			def jsp_preorder;
			if (n_order>0) {
				def l = Box.createHorizontalBox();
				l.add(new JLabel("Predefined Plots:"));
				for(opset in ts.findByName("Base").preconf_order_and_plot()) {
					def str = "<html>";
					str += opset['plot'].join(", ");
					if (opset['group']) {
						str += "<br/>groupby: "
						str += opset['group'].join("/");
					}
					if (opset['rows']) {
						str += "<br/>rows: ";
						str += opset['rows'].join(", ");
					}
					str += "</html>"
					def jb = new JButton(str);
					jb.addActionListener(new OrderAndPlotPresetButtonListener(ts, opset, this));
					l.add(jb);					
				};
				jsp_preorder = new JScrollPane(l);
			}
			
			frame.setPreferredSize(new Dimension(1024,384));
			// Resizing is very awkward, this is the best way I could
			// do it. Took me 3 weeks to figure this out (20091013).
			def jsp = new JScrollPane(jt2);
			frame.addComponentListener(new ResizeListener({ 
				jsp.preferredSize=new Dimension(
						(int)swing.panel.size.width-margin, 
						(int)swing.panel.size.height-preset_height-margin);
				jsp.revalidate();
				jsp_preorder.preferredSize=new Dimension(
						(int)swing.panel.size.width-margin,
						preset_height);
				jsp_preorder.revalidate();
				frame.validate();
			}));
			
			swing.panel.removeAll();
			if (n_order>0) {
				swing.panel.add(jsp_preorder, BorderLayout.CENTER);
			}
			swing.panel.add(jsp, BorderLayout.CENTER);
			
			// We need to change the File menu
			frame.setJMenuBar(menubar2);
			
			frame.pack();
			frame.show();
		});
		
		syntheticcolumn = swing.action(name:'Synthetic column', closure:{
			def formula = JOptionPane.showInputDialog(null, 
					"Please enter formula", 
					"Formula", 
					JOptionPane.QUESTION_MESSAGE, null, null, "");
			if (formula) {
				def t = ts.findByName("Synthetic");
				t.addSyntheticColumn(formula);
				ts.updateUpwardsFrom(t);
				jt2.model.fireTableStructureChanged();
				jt2.reinit();
				jt2.doLayout();
			}
		}
		);
		
		about = swing.action(name:'About', closure:{
			JOptionPane.showMessageDialog(frame, '''
(c) $Date$ Baltic Online Computer GmbH 
By:  Jochen Fritzenkötter
$URL$
$Revision$
						''', "About", 
					JOptionPane.INFORMATION_MESSAGE);
		}
		);
		menubar1 = swing.menuBar(id:'menubar1') {
			menu("File") {
				menuItem(action:open)
				menuItem(action:about)
				menuItem(action:exit)
			}
			menu("Column") {
				menuItem(action:syntheticcolumn);
			}			
		};
		menubar2 = swing.menuBar(id:'menubar2') {
			menu("File") {
				menuItem(action:addf)
				menuItem(action:remf)
				menuItem(action:about)
				menuItem(action:exit)
			}
			menu("Column") {
				menuItem(action:syntheticcolumn);
			}			
		};
		
		init();
	}
	
	private void init() {		
		jt2 = null;
		ts = new TableStack();
		plots.each { it.kill() };
		plots = [];
		fixedplots.each { it.kill() };
		fixedplots = [];
		if (frame) frame.dispose();
		
		frame = swing.frame(id:'frame', title:"VdbenchExplorer",
				locationRelativeTo:null) {
					panel(id:'panel') {
						label("Please open a table file with File->Open!");
					}
				}
		
		frame.setJMenuBar(menubar1);
		
		frame.pack();
		frame.show();
	}
	
	JPopupMenu createPopupMenuCell(int row, int col) {
		def fT = ts.findByName("RowFilter");
		def popup = swing.popupMenu(id:"popupcell") {
			menuItem() {
				action(name:"Only this value", closure: {
					fT.addFilter(jt2.model.getColumn(col).columnHead.name,
							jt2.model.getColumn(col).getRow(row).val, true);
					//println "r="+row+" c="+col+" v="+jt2.model.getColumn(col).getRow(row).val;
					ts.updateUpwardsFrom(fT);
					jt2.model.fireTableDataChanged();
					jt2.repaint();
					jt2.tableHeader.repaint();
					updatePlots();
				})
			};
			menuItem() {
				action(name:"Exlude this value", closure: {
					fT.addFilter(jt2.model.getColumn(col).columnHead.name, 
							jt2.model.getColumn(col).getRow(row).val, false);
					//println "r="+row+" c="+col+" v="+jt2.model.getColumn(col).getRow(row).val;
					ts.updateUpwardsFrom(fT);
					jt2.model.fireTableDataChanged();
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

					updatePlots();
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
						jt2.model.fireTableDataChanged();
						jt2.repaint();
						jt2.tableHeader.repaint();
						updatePlots();
					});
				}
			}
			separator();
			menuItem() {
				action(name:"Remove column", closure: {
					def cT = ts.findByName("Synthetic");
					def fT = ts.findByName("RowFilter");
					jt2.model.getColumn(col).groupby=false;
					fT.removeColFilters(jt2.model.getColumn(col).columnHead.name);
					cT.removeColumn(jt2.model.getColumn(col));
					ts.updateUpwardsFrom(cT);
					ts.updateUpwardsFrom(fT);
					jt2.model.fireTableStructureChanged();
					jt2.reinit();
					jt2.doLayout();					
				});
			}
		};		
	}
	
	synchronized void fix_plot(Plot p) {
		plots = plots - p;
		fixedplots << p;
		fixedplots = (fixedplots + p).unique();
		updatePlots();
	}
	
	synchronized void updatePlots() {
		int count=0;
		def xcol=-1;
		def ycols=[];
		
		// Determine the plottable columns
		// The first column that is checked as plot is always the x column
		0.upto(jt2.model.getColumnCount()-1) { col ->
			int tmp = jt2.convertColumnIndexToModel(col);
			if (jt2.model.getColumn(tmp).plotted) {
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
					reuse[0].reinit(xcol, ycol, groupby);
					//println "cx, cy="+reuse[0].cx+" "+reuse[0].cy;
					plots << reuse[0];
					reuse.remove(0);
				} else {
					//println "create new";
					//println "adding "+xcol+" "+ycol;
					plots << new Plot(xcol, ycol, this, groupby);
				}
			}
		}
		//println "After2: "+plots;
		//println "Reuse2: "+reuse;
		
		reuse.each { plot ->
			plot.kill();
		}
		
		def g = [];
		for (i in 0..(jt2.model.getColumnCount()-1)) {
			if (jt2.model.getColumn(i).groupby) {
				g << jt2.model.getColumn(i); 
			}
		}
		groupby = (Column[])g;			
		plots.each { plot -> 
			plot.groupby(groupby);
		};		
	}
	
	void repaintTableHeader() {
		jt2.tableHeader.repaint();
	}
	
	TableStack getTableStack() {
		return ts;
	}
	
	private ArrayList find_files(File path, SimpleFileFilter sff) {
		def fl = [];
		File[] files = path.listFiles();
		for(File f : files) {
			if (f.isFile() && sff.accept(f)) {
				//println "+ "+f.toString();
				fl << f;
			} else if (f.isDirectory()) {
				fl << find_files(f, sff)
			}
		}
		//if (fl.flatten().size()>0) { println "= "+fl.flatten().collect {it.toString()}; }
		return fl.flatten();
	}

}

if (new File("tdata").exists()) {

	assert Column.guessType((String[])["1", "2", "3"]) == Type.INT;
	assert Column.guessType((String[])["1.1", "2", "3e-5"]) == Type.FLOAT;
	assert Column.guessType((String[])["1.a", "e2", "1.0eg7"]) == Type.LABEL;
	assert Column.guessType((String[])["09:01:02", "23:57:58"]) == Type.TIME;
	assert Column.guessType((String[])["09:01:02.123", "23:57:58.987"]) == Type.TIME;
	
	v = new VdbenchFlatfileTable(new File("tdata/VdbenchFlatfile.html"));
	
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
	
	v = new AsciiFileTable("tdata/AsciiFileTable.txt");
	
	assert v.getColumn(0).columnHead.name == "int1";
	assert v.getColumn(1).columnHead.name == "int2";
	assert v.getColumn(3).columnHead.name == "float2";
	assert v.getColumn(4).columnHead.name == "string1";
	
	assert v.getColumn(0).columnType == Type.INT;
	assert v.getColumn(1).columnType == Type.INT;
	assert v.getColumn(3).columnType == Type.FLOAT;
	assert v.getColumn(4).columnType == Type.LABEL;
	
	assert v.getColumn(0).cardinality() == v.getColumn(0).length();
	assert v.getColumn(1).cardinality() == 1;
	
	0.upto(v.getColumnCount()-1) {
		col=v.getColumn(it);
		assert col.columnType == col.guessType();
		assert (1..col.length()).contains(col.cardinality()); 
	}
	
	v = new AsciiFileTable("tdata/AsciiFileTable_Space.txt");
	
	assert v.getColumn(0).columnHead.name == "int1";
	assert v.getColumn(1).columnHead.name == "int2";
	assert v.getColumn(3).columnHead.name == "float2";
	assert v.getColumn(4).columnHead.name == "string1";
	
	assert v.getColumn(0).columnType == Type.INT;
	assert v.getColumn(1).columnType == Type.INT;
	assert v.getColumn(3).columnType == Type.FLOAT;
	assert v.getColumn(4).columnType == Type.LABEL;
	
	assert v.getColumn(0).cardinality() == v.getColumn(0).length();
	assert v.getColumn(1).cardinality() == 1;
	
	0.upto(v.getColumnCount()-1) {
		col=v.getColumn(it);
		assert col.columnType == col.guessType();
		assert (1..col.length()).contains(col.cardinality()); 
	}
	
	v = new SarOutputTable("tdata/sar_multiline.data");
	
	assert v.getColumn(0).columnHead.name == "tod";
	assert v.getColumn(1).columnHead.name == "%usr";
	assert v.getColumn(3).columnHead.name == "%wio";
	assert v.getColumn(5).columnHead.name == "device";
	assert v.getColumn(12).columnHead.name == "runq-sz";
	
	assert v.getColumn(0).columnType == Type.TIME;
	assert v.getColumn(1).columnType == Type.INT;
	assert v.getColumn(3).columnType == Type.INT;
	assert v.getColumn(5).columnType == Type.LABEL;
	assert v.getColumn(12).columnType == Type.FLOAT;
	
	assert v.getValueAt(0,5) == "fd0";
	assert v.getValueAt(22,29) == 547;
	
	assert v.getColumn(0).cardinality()*21 == v.getColumn(0).length();
	assert v.getColumn(16).cardinality() == 1;
	
	0.upto(v.getColumnCount()-1) {
		col=v.getColumn(it);
		assert col.columnType == col.guessType();
		assert (1..col.length()).contains(col.cardinality()); 
	}
	
	v = new SarOutputTable("tdata/sar_simple.data");
	
	assert v.getColumn(0).columnHead.name == "tod";
	assert v.getColumn(1).columnHead.name == "scall/s";
	assert v.getColumn(4).columnHead.name == "fork/s";
	
	assert v.getColumn(0).columnType == Type.TIME;
	assert v.getColumn(1).columnType == Type.INT;
	assert v.getColumn(4).columnType == Type.FLOAT;
	
	assert v.getValueAt(0,1) == 2792;
	assert v.getValueAt(1,4) == 1.95;
	
	assert v.getColumn(0).length() == 10;
	assert v.getColumn(0).cardinality() == v.getColumn(0).length();
	
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
	t3.addFilter(t1.getColumn(0).columnHead.name, "b", false);
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
	t1.add(new SimpleColumn("c1", "c1", (String[])["a","b","a","c","a","c"]));
	t1.add(new SimpleColumn("c2", "c2", (String[])["b","b","b","d","b","d"]));
	t1.add(new SimpleColumn("c3", "c3", (String[])["a","b","a","a","b","a"]));
	t2 = new SortedUniqueTable(t1);
	assert t2.getRowCount() == 4;
	assert t2.getValueAt(0,0) == "a";
	assert t2.getValueAt(0,1) == "b";
	assert t2.getValueAt(0,2) == "a";
	assert t2.getValueAt(1,2) == "b";
	
	t1 = new SimpleTable("Test1", "Test1");
	t1.add(new SimpleColumn("c1", "c1", Type.INT, (String[])["0","1","0","2","0","0"]));
	t1.add(new SimpleColumn("c2", "c2", Type.INT, (String[])["1","1","0","3","1","3"]));
	t1.add(new SimpleColumn("c3", "c3", Type.INT, (String[])["0","1","2","0","1","3"]));
	ts = new TableStack();
	ts.add(t1);
	ts.add(ColumnFilteredTable.class);
	t2=ts.findByClass(ColumnFilteredTable.class);
	t2.addSyntheticColumn("'c1'+'c2'");
	t2.addSyntheticColumn("'c2'*'c3'");
	assert t2.getValueAt(3,0) == 2;
	assert t2.getValueAt(4,1) == 1; 
	assert t2.getValueAt(0,3) == 1;
	assert t2.getValueAt(1,3) == 2;
	assert t2.getValueAt(3,3) == 5;
	assert t2.getValueAt(0,4) == 0;
	assert t2.getValueAt(1,4) == 1;
	assert t2.getValueAt(5,4) == 9;
	
	assert MergedTable.makeShortNames(["Test"]) == ["Test":"~"];
	assert MergedTable.makeShortNames(["Test1","Test2"]) == ["Test1":"~1", "Test2":"~2"];
	assert MergedTable.makeShortNames(["1Test","2Test"]) == ["1Test":"1~", "2Test":"2~"];
	assert MergedTable.makeShortNames(["Waelzer","Walzer"]) == ["Waelzer":"~e~", "Walzer":"~"];
	assert MergedTable.makeShortNames(["Walzer","Waelzer"]) == ["Waelzer":"~e~", "Walzer":"~"];
	assert MergedTable.makeShortNames(["Waelzer","Walzer","Wasser"]) == ["Waelzer":"~elz~", "Walzer":"~lz~", "Wasser":"~ss~"];
	assert MergedTable.makeShortNames(["abcde","xyz"]) == ["abcde":"abcde", "xyz":"xyz"];
	assert MergedTable.makeShortNames(["abcde","abcdefg"]) == ["abcde":"~", "abcdefg":"~fg"];
	assert MergedTable.makeShortNames(["abcdefg","cdefg"]) == ["cdefg":"~", "abcdefg":"ab~"];
	assert MergedTable.makeShortNames(["a/b/f","a/f"]) == ["a/b/f":"~b~", "a/f":"~"];
	assert MergedTable.makeShortNames(["abcde","abcde"]) == ["abcde":"~"];
	assert MergedTable.makeShortNames(["abcde","abcde","abc"]) == ["abcde":"~de", "abc":"~"];
	assert MergedTable.makeShortNames(["abc","abcde","abc"]) == ["abcde":"~de", "abc":"~"];	
	
	t2=new SimpleTable("Test2", "Test2");
	t2.add(new SimpleColumn("c1", "c1", Type.INT, (String[])["0","7"]));
	t2.add(new SimpleColumn("c2", "c2", Type.INT, (String[])["6","1"]));
	ts=new TableStack();
	ts.add(t1);
	ts.add(MergedTable.class, "Merge");
	t3=ts.findByName("Merge");
	assert t3.rowCount == 6;
	assert t3.getValueAt(1,0) == 1;
	assert t3.getValueAt(3,0) == 2;
	assert t3.getValueAt(3,1) == 3;
	t3.add(t2);
	assert t3.rowCount == 8;
	assert t3.getColumnByName("c1").length() == 8;
	assert t3.getValueAt(0,0) == '~1';
	assert t3.getValueAt(5,0) == '~1';
	assert t3.getValueAt(6,0) == '~2';
	assert t3.getValueAt(7,0) == '~2';
	assert t3.getValueAt(1,1) == 1;
	assert t3.getValueAt(3,1) == 2;
	assert t3.getValueAt(7,1) == 7;
	assert t3.getValueAt(3,2) == 3;
	assert t3.getValueAt(6,2) == 6;
}
	
v = new VdbenchExplorerGUI();

