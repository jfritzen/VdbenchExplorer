package de.bo.vdbenchexplorer;
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
	JPanel p;
	Column cx;
	Column cy;
	
	Plot(Column c1, Column c2) {
		this.cx = c1;
		this.cy = c2;

		plotFrame = new JFrame();
		p = new JPanel(new BorderLayout());
		plotFrame.getContentPane().add(p);
		init();
	}	

	void reinit(Column c1, Column c2) {
		this.cx = c1;
		this.cy = c2;
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
		
		Graph_2D graph = new Graph_2D(gs);

		// TO FIX: When the window is resized, the axis is scaled 
		// correctly but the labels do not and loose their positions
		// relative to the axis tick marks.  
		if (cx.columnType == Type.LABEL) {
			gs.setDrawTicLabels(GraphSettings.X_AXIS, false);
			cx.symbols.each {
				// Use GraphLabel.DATA when you specify points in data 
				// coordinates
				l = new GraphLabel(GraphLabel.DATA, it);
				println graph.toX(cx.l2d[it]);
				l.setDataLocation(cx.l2d[it], 0.0D);
				gs.addLabel(l);
			}
		}
		if (cy.columnType == Type.LABEL) {
			gs.setDrawTicLabels(GraphSettings.Y_AXIS, false);
			cy.symbols.each {
				// Use GraphLabel.DATA when you specify points in data 
				// coordinates
				l = new GraphLabel(GraphLabel.DATA, it);
				println graph.toY(cy.l2d[it]);
				l.setDataLocation(0.0D, cy.l2d[it]);
				gs.addLabel(l);
			}
		}		
		
		plotFrame.title=(cx.table.description!=null)?cx.table.description:\
				cx.columnHead.name+" - "+cy.columnHead.name;
		plotFrame.addWindowListener(new WindowAdapter() {
		    public void windowClosing(WindowEvent e) {
		      plotFrame.dispose();
		      plotFrame = null;
		    }
		  });

		p.add(graph,BorderLayout.CENTER);
		plotFrame.pack();
		plotFrame.show();

		DataArray da = new DataArray();
		Vector v = new Vector();

		def points = [];
		0.upto(cx.length()-1) {
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
			plotFrame=null;
		}
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
    	        	setBackground(header.foreground);
        			setForeground(header.background);
    	        } else {
    	        	setBackground(header.background);
        			setForeground(header.foreground);
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

class VdbenchExplorerGUI {
	def swing;
	def frame;
	def plots = [];
	def fixedplots = [];
	int margin = 10;
	JTable2 jt2;

    private final def exit;
    private final def open;
    
	VdbenchExplorerGUI() {
		swing = new SwingBuilder();

		exit = swing.action(name:'Exit', closure:{System.exit(0)});
		open = swing.action(name:'Open', closure:{
			def t = new VdbenchFlatfileTable("/Users/jf/BO/masterdata/XXX/flatfile.html");
			jt2 = new JTable2(t);

			// Registering for right-clicks on the TableHeader
			jt2.tableHeader.addMouseListener(new PopupListener({
				// Find the column in which the MouseEvent was triggered
				int col = jt2.getColumnModel().getColumnIndexAtX(it.getX());
				createPopupMenu(jt2.convertColumnIndexToModel(col)).
					show((Component) it.getSource(), it.getX(), it.getY());
			}));

			jt2.columnModel.addColumnModelListener(new TCMListener({
				updatePlots();
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

	JPopupMenu createPopupMenu(int col) {
		def popup = swing.popupMenu(id:"popup") {
			menuItem() {
				def tag=jt2.model.getColumn(col).plotted?"Don't plot":'Plot';
				action(name:tag, closure: {
					jt2.model.getColumn(col).plotted=
						!jt2.model.getColumn(col).plotted;
					println(jt2.model.getColumn(col).columnHead.description);
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
					plots << new Plot(xcol, ycol);
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

/*
p1 = new Plot(v.getColumn(0),v.getColumn(4));
p2 = new Plot(v.getColumn(4),v.getColumn(5));
p2 = new Plot(v.getColumn(0),v.getColumn(5));
p3 = new Plot(v.getColumn(0),v.getColumn(1));
*/

v = new VdbenchExplorerGUI();

