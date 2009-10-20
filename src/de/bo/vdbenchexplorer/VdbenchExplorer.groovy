package de.bo.vdbenchexplorer;
import groovy.swing.SwingBuilder;
import java.util.regex.Matcher;
import java.util.Date;
import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.TableModel;
import javax.swing.table.TableColumnModel;
import javax.swing.event.TableModelListener;
import jplot.*;

enum Type { DATETIME, TIME, LABEL, INT, FLOAT };

class Table implements TableModel {
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

	// Own methods
	
	Column getColumn(int col) {
		return cols[col];
	}

	Cell[] getRow(int row) {
		cols*.getRow(row).toList();
	}

	Cell getCellAt(int row, int col) {
		return cols[col].getRow(row);
	}

}

class VdbenchFlatfileTable extends Table {
	def h=[:], heads;
	Matcher m;
	
	VdbenchFlatfileTable(String file) {
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
			cols << new VdbenchFlatfileColumn(t, new ColumnHead(name:it, 
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
}

abstract class Column {
	Type columnType = Type.LABEL;
	ColumnHead columnHead;
	Table table;
	boolean masked = false;
	boolean plotted = false;
	def d2l = [:], l2d = [:], symbols;
	
	abstract Cell getRow(int row);
	abstract void add(Cell c);
	abstract Type guessType();
	abstract int length();
	abstract int cardinality();
	abstract void initSymbols();
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
}

class VdbenchFlatfileColumn extends Column {
	def cells=[];
	
	VdbenchFlatfileColumn(Table t, ColumnHead ch) {
		this.columnHead = ch;
		this.table = t;
	}

	void add(Cell c) {
		this.cells << c;
	}

	Cell getRow(int row) {
		return cells[row];
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
		return t.getColumn(col).columnType;
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
	Column cx;
	Column cy;
	
	Plot(Column c1, Column c2) {
		this.cx = c1;
		this.cy = c2;
		
		double[] x = c1.getDoubles();
		double[] y = c2.getDoubles();

		GraphSettings gs = new GraphSettings();
		gs.setMinValue(GraphSettings.X_AXIS, x.toList().min());
		gs.setMaxValue(GraphSettings.X_AXIS, x.toList().max());
		gs.setMinValue(GraphSettings.Y_AXIS, y.toList().min());
		gs.setMaxValue(GraphSettings.Y_AXIS, y.toList().max());

		GraphLabel l=new GraphLabel(GraphLabel.XLABEL, 
				c1.columnHead.description);
		gs.addLabel(l);
		l=new GraphLabel(GraphLabel.YLABEL, c2.columnHead.description);
		l.setRotation(Math.PI*1.5);
		gs.addLabel(l);
		
		Graph_2D graph = new Graph_2D(gs);

		// TO FIX: When the window is resized, the axis is scaled 
		// correctly but the labels do not and loose their positions
		// relative to the axis tick marks.  
		if (c1.columnType == Type.LABEL) {
			gs.setDrawTicLabels(GraphSettings.X_AXIS, false);
			c1.symbols.each {
				// Use GraphLabel.DATA when you specify points in data 
				// coordinates
				l = new GraphLabel(GraphLabel.DATA, it);
				println graph.toX(c1.l2d[it]);
				l.setDataLocation(c1.l2d[it], 0.0D);
				gs.addLabel(l);
			}
		}
		if (c2.columnType == Type.LABEL) {
			gs.setDrawTicLabels(GraphSettings.Y_AXIS, false);
			c2.symbols.each {
				// Use GraphLabel.DATA when you specify points in data 
				// coordinates
				l = new GraphLabel(GraphLabel.DATA, it);
				println graph.toY(c2.l2d[it]);
				l.setDataLocation(0.0D, c2.l2d[it]);
				gs.addLabel(l);
			}
		}		
		
		def s = (c1.table.description!=null)?c1.table.description:\
				c1.columnHead.name+" - "+c2.columnHead.name;
		plotFrame = new JFrame(s);
		plotFrame.addWindowListener(new WindowAdapter() {
		    public void windowClosing(WindowEvent e) {
		      plotFrame.dispose();
		      plotFrame = null;
		    }
		  });
	
		JPanel p = new JPanel(new BorderLayout());
		p.add(graph,BorderLayout.CENTER);
		plotFrame.getContentPane().add(p);
		plotFrame.pack();
		plotFrame.show();

		DataArray da = new DataArray();
		Vector v = new Vector();

		def points = [];
		0.upto(c1.length()-1) {
			points << [x[it], y[it]];
		}
		points = points.sort { it[0] };
		points.each { da.addPoint(it[0], it[1]) };
		da.setDrawSymbol(true);
		da.setSymbol(2);
		
		v.add(da);
		graph.show(v);
	}

	void kill() {
		if (plotFrame!=null) {
			plotFrame.dispose();
		}
	}
}

// Needed for resizing the frame such that the JScrollPane's size is updated
class ResizeListener extends ComponentAdapter {
	Closure closure;
	
	ResizeListener(Closure c) {
		closure=c;
	}
	
	public void componentResized(ComponentEvent e) {
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

class JTable2 extends JTable {
	int margin = 5;
	TableModel tm;

	JTable2(TableModel tm, VdbenchExplorerGUI v) {
		super(tm);
		this.tm = tm;
		this.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);

		0.upto(tm.columnCount-1) { col ->
			this.getColumnModel().getColumn(col).preferredWidth=
				this.columnWidth(col);
		}
		this.tableHeader.addMouseListener(new PopupListener({
			// Find the column in which the MouseEvent was triggered
			// TODO: Doesn't get rearranged columns yet
			0.upto(tm.columnCount-1) { col ->
				if (it.getSource().getHeaderRect(col).
						contains(it.getX(), it.getY())) {
					v.createPopupMenu(col).
					show((Component) it.getSource(), it.getX(), it.getY());
				}
			}
		}));
		
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

class VdbenchExplorerGUI {
	def swing;
	def frame;
	def plots = [];
	Table t;
	int margin = 10;

    private final def exit;
    private final def open;
    
	VdbenchExplorerGUI() {
		swing = new SwingBuilder();

		exit = swing.action(name:'Exit', closure:{System.exit(0)});
		open = swing.action(name:'Open', closure:{
			t = new VdbenchFlatfileTable("/Users/jf/BO/masterdata/XXX/flatfile.html");
			def jsp = new JScrollPane(new JTable2(t, this));
			// Resizing is very awkward, this is the best way I could
			// do it. Took me 3 weeks to figure this out (20091013).
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

	JPopupMenu createPopupMenu(int col) {
		def popup = swing.popupMenu(id:"popup") {
			menuItem() {
				action(name:'Plot', closure: {
					t.getColumn(col).plotted=!t.getColumn(col).plotted;
					println(t.getColumn(col).columnHead.description);
					updatePlots();
				})
			};
		};		
	}

	void updatePlots() {
		int count=0;
		def xcol=-1;
		def ycols=[];

		// Determine the plottable columns
		// The first column that is checked as plot is always the x column
		0.upto(t.getColumnCount()-1) { col ->
			if (t.getColumn(col).plotted) {
				if (count==0) {
					xcol = t.getColumn(col);
				} else {
					ycols << t.getColumn(col);
				}
				count++;
			}
		}

		// Remove all plots that are not needed anymore. 
		def plots2 = [];
		plots.each { plot ->
			if (plot.plotFrame == null) {
				return;
			}
			
			if (plot.cx!=xcol) {
				plot.kill();
				return;
			}
			
			boolean found = false;
			ycols.each { ycol ->
				if (ycol==plot.cy) {
					found=true;
				}
			}
			if (!found) {
				plot.kill();
				return;
			}
			plots2 << plot;
		}
		plots = plots2;

		// Create all plots that are needed and that do not already exist
		ycols.each { ycol ->
			boolean found = false;
			plots.each { plot ->
				if (plot.cy==ycol) {
					found=true;
				}
			}
			if (!found) {
				plots << new Plot(xcol, ycol);
			}
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

/*
p1 = new Plot(v.getColumn(0),v.getColumn(4));
p2 = new Plot(v.getColumn(4),v.getColumn(5));
p2 = new Plot(v.getColumn(0),v.getColumn(5));
p3 = new Plot(v.getColumn(0),v.getColumn(1));
*/

v = new VdbenchExplorerGUI();

