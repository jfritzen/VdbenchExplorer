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

	void add(Column c) {
		cols << c;
	}
	
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
			cols << new SimpleColumn(t, new ColumnHead(name:it, 
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

class SortedTable extends Table {
	Table masterTable;
	JTable jt=null;
	private def rowRealToVirt;
	private def rowVirtToReal;
	
	SortedTable(Table t) {
		this(t, null);
	}

	SortedTable(Table t, JTable jt) {
		this.masterTable=t;
		this.jt=jt;
		def c;
		name=t.name;
		description=t.description;
		masterTable.cols.each { col ->
			c = new ProxyColumn(this, col);
			cols << c;
		}
		rowRealToVirt = 0..(c.length()-1);
		rowVirtToReal = 0..(c.length()-1);
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
		
		rowVirtToReal = rowPermutation;
		rowRealToVirt=[];
		(0..cols[0].length()-1).each {
			rowRealToVirt[rowPermutation[it]]=it;
		}
		
		return;
	}

	int real2virt(int row) {
		return rowRealToVirt[row];
	}
	
	int virt2real(int row) {
		return rowVirtToReal[row];
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
	private def rowRealToVirt=[:];
	private def rowVirtToReal=[:];
	def closures=[];

	RowFilteredTable(Table t) {
		this(t, null);
	}

	RowFilteredTable(Table t, JTable jt) {
		this.masterTable=t;
		this.jt=jt;
		def c;
		name=t.name;
		description=t.description;
		masterTable.cols.each { col ->
			c = new ProxyColumn(this, col);
			cols << c;
		}
		0.upto(masterTable.cols[0].length()-1) {
			rowRealToVirt[it]=it;
			rowVirtToReal[it]=it;
		}
		println "v2r="+rowVirtToReal+" r2v="+rowRealToVirt;
	}

	void addFilter(int col, String[] vals, boolean inverse) {
		closures << { row ->
			def val = masterTable.getColumn(col).getRow(row).val;
			boolean ret=vals.grep(val);
			ret=inverse?!ret:ret;
			println "row="+row+" val="+val+" ret="+ret+" vals="+vals;
			return ret;
		};
	}

	void removeFilter() {
		closures.pop();
	}

	void removeAllFilters() {
		closures = [];
	}

	int filterCount() {
		return closures.size();
	}
	
	boolean matches(int row) {
		for(closure in closures) {
			if (closure(row)) {
				return true;
			}
		}
	}
	
	void filter() {
		int row=0;
		rowVirtToReal=[:];
		rowRealToVirt=[:];
		0.upto(masterTable.getColumn(0).length()-1) {
			println "it="+it+" row="+row;
			if (!matches(it)) {
				println masterTable.getRow(it).toString();
				rowVirtToReal[row]=it;
				rowRealToVirt[it]=row;
				row++;
			}
		}
		println "v2r="+rowVirtToReal+" r2v="+rowRealToVirt;
	}

	int real2virt(int row) {
		return rowRealToVirt[row];
	}
	
	int virt2real(int row) {
		return rowVirtToReal[row];
	}

	int length() {
		return rowVirtToReal.keySet().size();
	}
}

abstract class Column {
	private Type columnType = Type.LABEL;
	ColumnHead columnHead;
	Table table;
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
	abstract void setRow(int row, Cell c);
	abstract void add(Cell c);
	abstract void removeAll();
	abstract Type guessType();
	abstract int length();
	abstract int cardinality();
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
	
	SimpleColumn(Table t, ColumnHead ch) {
		this.columnHead = ch;
		this.table = t;
	}

	SimpleColumn(Table t, String head, String desc, String[] vals) {
		this.columnHead = new ColumnHead(name:head, description:desc);
		this.table = t;
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
	def table;
	private Column realCol;
	
	ProxyColumn(SortedTable t, Column c) {
		this.realCol = c;
		this.table = t;
		this.columnHead = c.columnHead;
	}

	ProxyColumn(RowFilteredTable t, Column c) {
		this.realCol = c;
		this.table = t;
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
		realCol.setRow(table.virt2real(row), c);
	}

	Cell getRow(int row) {
		return realCol.getRow(table.virt2real(row));
	}

	int length() {
		return table.length();
	}

	Type guessType() {
		return realCol.guessType();
	}

	int cardinality() {
		return realCol.cardinality();
	}

	String[] distinctVals() {
		return realCol.distinctVals();
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
	
	double[] getDoubles() {
		def dr = realCol.getDoubles();
		def dv = [];
		0.upto(dr.size()-1) {
			dv << dr[table.virt2real(it)];
		};
		return dv;
	}

	// Own methods
	Cell getRealRow(int row) {
		return realCol.getRow(row);
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
		this.cx = c1;
		this.cy = c2;
		this.groupby = g;

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

		plotFrame.title=(cx.table.description!=null)?cx.table.description:\
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
        	        	setForeground(Color.YELLOW);
            			setBackground(header.foreground);
    				} else {
    					setForeground(header.background);
    					setBackground(header.foreground);
    				}
    			} else if (((Table)table.model).getColumn(col).groupby) {
    	        	setBackground(Color.YELLOW);
        			setForeground(header.foreground);
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
	private JTable2 jt2;
	private Column groupby = null;
	private int groupbylimit = 10;
	private int delay = 500;
	private java.util.Timer redrawRequest = null;
	private def filter = null;

    private final def exit;
    private final def open;
    
	VdbenchExplorerGUI() {
		swing = new SwingBuilder();

		exit = swing.action(name:'Exit', closure:{System.exit(0)});
		open = swing.action(name:'Open', closure:{
			def t1 = new VdbenchFlatfileTable("/Users/jf/BO/masterdata/XXX/flatfile.html");
//			def t1 = new VdbenchFlatfileTable("/Users/jf/BO/masterdata/XXX/flatfile.html");
			def t2 = new SortedTable(t1);
			def t3 = new RowFilteredTable(t2);
			jt2 = new JTable2(t3);
			t2.setJTable(jt2);
			filter = t3;
			
			// Registering for right-clicks on the TableHeader
			jt2.tableHeader.addMouseListener(new PopupListener({
				// Find the column in which the MouseEvent was triggered
				int col = jt2.getColumnModel().getColumnIndexAtX(it.getX());
				createPopupMenu(jt2.convertColumnIndexToModel(col)).
					show((Component) it.getSource(), it.getX(), it.getY());
			}));

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
					t2.sort();
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

					//updatePlots();
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

t1 = new Table();
t1.add(new SimpleColumn(t1, "c1", "c1", (String[])["a","b","a","c","a","a"]));
t1.add(new SimpleColumn(t1, "c2", "c2", (String[])["b","b","a","d","b","d"]));
t1.add(new SimpleColumn(t1, "c3", "c3", (String[])["a","b","c","a","b","d"]));
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
t3.addFilter(0, (String[])["b"], false);
t3.filter();
assert t3.length()==5;
0.upto(4) {
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

v = new VdbenchExplorerGUI();

