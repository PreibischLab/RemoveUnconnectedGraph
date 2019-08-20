package graph;

import java.awt.Color;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.util.ArrayList;

import fiji.tool.AbstractTool;
import fiji.tool.SliceListener;
import fiji.tool.SliceObserver;
import ij.IJ;
import ij.ImagePlus;
import ij.gui.ImageCanvas;
import ij.gui.OvalRoi;
import ij.gui.Overlay;
import ij.gui.PolygonRoi;
import ij.gui.TextRoi;
import ij.plugin.frame.RoiManager;
import mpicbg.imglib.cursor.LocalizableByDimCursor;
import mpicbg.imglib.image.Image;
import mpicbg.imglib.image.display.imagej.ImageJFunctions;
import mpicbg.imglib.outofbounds.OutOfBoundsStrategyValueFactory;
import mpicbg.imglib.type.numeric.RealType;

/**
 * Handles mouse & key events
 * 
 * @author Stephan Preibisch
 */
public class MouseEventHandler< T extends RealType< T > > extends AbstractTool implements MouseMotionListener, MouseListener, KeyListener
{
	enum TrackingStatus{ NOT_INITIALIZED, PARTIALLY_TRACKED, FULLY_TRACKED };

	final ComputeUnconnected parent;
	final ImagePlus imp;
	final ImageCanvas canvas;
	final int channel;
	final SliceObserver sliceObserver;

	// the currently visible frame
	Image< T > img;

	LocalizableByDimCursor< T > randomAccess;
	int currentFrame;
	ArrayList< Node > nodes;
	//KDTree< Node > nodeTree;
	
	Segment segment = null;
	int x = 0, y = 0;
	boolean holdingKeyF = false;
	int xd = -1, yd = -1;

	boolean trackingMode = false;
	TrackingStatus trackingInitialized = TrackingStatus.NOT_INITIALIZED;

	// the location of the tracked segment in each frame
	final Segment[] segmentLocationPerFrame;

	// the location of the tracked node in each frame
	//final int[][] nodeLocationPerFrame;
	
	public MouseEventHandler( final ImagePlus imp, final int channel, final ComputeUnconnected parent ) 
	{ 
		this.imp = imp;
		this.channel = channel;
		this.canvas = imp.getCanvas();
		this.parent = parent;
		
		updateSource();
		imp.getWindow().toFront();

		//this.nodeLocationPerFrame = new int[ imp.getNFrames() ][ 2 ];
		this.segmentLocationPerFrame = new Segment[ imp.getNFrames() ];

		this.sliceObserver = new SliceObserver( imp, new ImagePlusListener() );
		this.clearToolsIfNecessary = true;
		
		// start the abstract tool
		this.run( null );	
	}
	
	protected void updateSource()
	{
		currentFrame = imp.getFrame();
		
		if ( imp.getNChannels() == 1 && imp.getNFrames() == 1 )
			img = ImageJFunctions.wrap( imp );
		else
			img = ImageJFunctions.wrap( new ImagePlus( "wrapped", imp.getStack().getProcessor( imp.getStackIndex( channel + 1, 1, currentFrame ) ) ) );
		
		this.randomAccess = img.createLocalizableByDimCursor();
		
		this.nodes = parent.analyzeNodes( img, currentFrame );
		
		//if ( trackingMode )
		//	this.nodeTree = new KDTree<Node>( nodes, nodes );
		//else
		//	this.nodeTree = null;
		
		imp.updateAndDraw();
		displayAllInformation();
	}
		
	/**
	 * @return - the x coordinate corrected for maginifcation
	 */
	public int getXCoordinate()  { return canvas.getSrcRect().x + (int)( x / canvas.getMagnification() ); }
	
	/**
	 * @return - the y coordinate corrected for maginifcation
	 */
	public int getYCoordinate()  { return canvas.getSrcRect().y + (int)( y / (float)canvas.getMagnification() ); }
	
	@Override
	final public void mouseMoved( final MouseEvent e ) 
	{
		x = e.getX();
		y = e.getY();
		
		if ( holdingKeyF && !trackingMode )
		{
			final int x0 = getXCoordinate();
			final int y0 = getYCoordinate();
			
			final int distX = Math.abs( x0 - xd );
			final int distY = Math.abs( y0 - yd );
			
			if ( distX > 1 || distY > 1 )
			{
				// draw a line
				if ( distX > distY )
				{
					final float yInc;
					float y1;
					if ( x0 < xd )
					{
						yInc = (float)( yd-y0 ) / (float)distX;
						y1 = y0;
					}
					else
					{
						yInc = (float)( y0-yd ) / (float)distX;
						y1 = yd;
					}
										
					for ( int x1 = Math.min( x0, xd ); x1 <= Math.max( x0, xd ); ++x1 )
					{
						randomAccess.setPosition( x1, 0 );
						randomAccess.setPosition( Math.round( y1 ), 1 );
						randomAccess.getType().setReal( 255 );
						
						y1 += yInc;
					}						
				}
				else
				{
					final float xInc;
					float x1;
					if ( y0 < yd )
					{
						xInc = (float)( xd-x0 ) / (float)distY;
						x1 = x0;
					}
					else
					{
						xInc = (float)( x0-xd ) / (float)distY;
						x1 = xd;
					}
										
					for ( int y1 = Math.min( y0, yd ); y1 <= Math.max( y0, yd ); ++y1 )
					{
						randomAccess.setPosition( y1, 1 );
						randomAccess.setPosition( Math.round( x1 ), 0 );
						randomAccess.getType().setReal( 255 );
						
						x1 += xInc;
					}						
				}
			}
			else	
			{
				randomAccess.setPosition( getXCoordinate(), 0 );
				randomAccess.setPosition( getYCoordinate(), 1 );
				randomAccess.getType().setReal( 255 );
			}

			xd = x0;
			yd = y0;
			
			imp.updateAndDraw();
		}
	}
	
	@Override
	final public void mouseClicked( final MouseEvent arg0 ) 
	{
		final int x = getXCoordinate();
		final int y = getYCoordinate();
		
		if ( trackingMode )
		{
			final Segment refSegment;
			
			if ( trackingInitialized == TrackingStatus.NOT_INITIALIZED )
			{
				updateSource();
				final int refFrame = imp.getFrame();

				// find the closest point on a path
				final int[] position = parent.findClosestPointOnPath( img, x, y, nodes );
				
				if ( position == null )
					return;

				// get the two nodes that are connected by this path
				refSegment = parent.findSegment( img, nodes, position );

				if ( refSegment == null )
				{
					trackingInitialized = TrackingStatus.NOT_INITIALIZED;
					return;
				}

				// TODO: Fix that it does not stop if it cannot track it through time
				segmentLocationPerFrame[ refFrame - 1 ] = refSegment;

				// show progress
				IJ.log( "Tracking forward and backwards through time ... " );
				displayAllInformation();

				// propagate back in time
				boolean success = trackForwardThroughTime( refFrame );

				// propagate forward in time
				success &= trackBackwardThroughTime( refFrame );

				imp.setPosition( imp.getStackIndex( imp.getChannel(), imp.getSlice(), refFrame ) );
				
				if ( !success )
					trackingInitialized = TrackingStatus.PARTIALLY_TRACKED;
				else
					trackingInitialized = TrackingStatus.FULLY_TRACKED;
			}
			else
			{
				// find the closest point on a path
				final int[] position = parent.findClosestPointOnPath( img, x, y, nodes );
				
				if ( position == null )
					return;

				// get the two nodes that are connected by this path
				refSegment = parent.findSegment( img, nodes, position );

				if ( refSegment == null )
					return;

				segmentLocationPerFrame[ imp.getFrame() - 1 ] = refSegment;
			}

			displayAllInformation();
		}
		else
		{
			// find the closest point on a path
			final int[] position = parent.findClosestPointOnPath( img, x, y, nodes );
			
			if ( position == null )
			{
				imp.setOverlay( null );
				segment = null;
				return;
			}
			
			// get the two nodes that are connected by this path
			this.segment = parent.findSegment( img, nodes, position );
			
			if ( this.segment == null )
			{
				imp.setOverlay( null );
				return;
			}
			
			Overlay o = new Overlay();
			OvalRoi o1 = new OvalRoi( segment.getNode1().getPosition()[ 0 ] - 1, segment.getNode1().getPosition()[ 1 ] - 1, 3, 3 );
			OvalRoi o2 = new OvalRoi( segment.getNode2().getPosition()[ 0 ] - 1, segment.getNode2().getPosition()[ 1 ] - 1, 3, 3 );
			o1.setStrokeColor( Color.GREEN );
			o2.setStrokeColor( Color.GREEN );
			o.add( o1 );
			o.add( o2 );
			
			PolygonRoi roi = segment.getPolygonRoi();
			roi.setStrokeColor( Color.RED );
			o.add( roi );
			imp.setOverlay( o );
		}
	}
	
	protected boolean trackForwardThroughTime( final int refFrame )
	{
		IJ.log( "nf= " + imp.getNFrames() );
		IJ.log( "refFrame= " + refFrame );

		// propagate forward in time
		for ( int t = refFrame + 1; t <= imp.getNFrames(); ++t )
		{
			IJ.log( "t= " + t );
			final Segment lastSegment = segmentLocationPerFrame[ t - 2 ];
			final ArrayList< int[] > lastPoints = lastSegment.getPoints();

			if ( lastPoints == null || lastPoints.size() == 0 )
			{
				IJ.log( "Last segment of " + t + " is null." );
				return false;
			}

			// triggers already updateSource() due to the SliceListener
			imp.setPosition( imp.getStackIndex( imp.getChannel(), imp.getSlice(), t ) );

			final Segment segment = findSegmentInFrame( lastPoints );

			if ( segment == null )
			{
				IJ.log( "There was a problem finding corresponding segment in frame " + t + " (Forward tracking), node = null" );
				return false;
			}
			else
			{
				segmentLocationPerFrame[ t - 1 ] = segment;
			}
		}
		return true;
	}

	protected boolean trackBackwardThroughTime( final int refFrame )
	{
		for ( int t = refFrame - 1; t >= 1; --t )
		{
			final Segment lastSegment = segmentLocationPerFrame[ t ];
			final ArrayList< int[] > lastPoints = lastSegment.getPoints();

			if ( lastPoints == null || lastPoints.size() == 0 )
			{
				IJ.log( "Last segment of " + t + " is null." );
				return false;
			}

			// triggers already updateSource() due to the SliceListener
			imp.setPosition( imp.getStackIndex( imp.getChannel(), imp.getSlice(), t ) );

			final Segment segment = findSegmentInFrame( lastPoints );

			if ( segment == null )
			{
				IJ.log( "There was a problem finding corresponding segment in frame " + t + " (Backward tracking), node = null" );
				return false;
			}
			else
			{
				segmentLocationPerFrame[ t - 1 ] = segment;
			}
		}
		
		return true;
	}

	protected Segment findSegmentInFrame( final ArrayList< int[] > lastPoints )
	{
		int[] position = null;
		Segment segment = null;

		for ( int i = 0; i < 3; ++i )
		{
			final int[] lastPoint;

			if ( i == 0 )
				lastPoint = lastPoints.get( lastPoints.size() / 2 );
			else if ( i == 1 )
				lastPoint = lastPoints.get( lastPoints.size() / 3 );
			else
				lastPoint = lastPoints.get( Math.min( lastPoints.size() - 1, ( lastPoints.size() / 3 ) * 2 ) );

			// find the closest segment relative to the previous time-point
			position = parent.findClosestPointOnPath( img, lastPoint[ 0 ], lastPoint[ 1 ], nodes );

			if ( position == null )
				continue;

			// get the two nodes that are connected by this path
			segment = parent.findSegment( img, nodes, position );

			if ( segment != null )
				break;
		}

		return segment;
	}

	@Override
	public void keyPressed(KeyEvent arg0) 
	{
		if ( arg0.getKeyChar() == 'd' || arg0.getKeyChar() == 'D' )
		{
			arg0.consume();
			imp.setOverlay( null );
			
			if ( this.segment == null )
			{
				IJ.log( "No segment selected for deletion (activate the tool and left click on a segment)" );
			}
			else
			{
				// set all points on the segment to 0
				LocalizableByDimCursor< T > randomAccess = img.createLocalizableByDimCursor( new OutOfBoundsStrategyValueFactory< T >() );
				
				for ( final int[] location : segment.getPoints() )
				{
					randomAccess.setPosition( location );
					randomAccess.getType().setZero();
				}
				
				// change the nodes to one connection less
				parent.reduceNode( segment.getNode1(), randomAccess, nodes );
				parent.reduceNode( segment.getNode2(), randomAccess, nodes );
				
				imp.updateAndDraw();
				parent.printNodeStatistics( nodes );
				//parent.drawNodes( nodes, img.getDimensions() );
			}
			
		}
		else if ( arg0.getKeyChar() == 's' || arg0.getKeyChar() == 'S' )
		{
			// show all nodes as overlay
			arg0.consume();
			
			if ( !trackingMode )
				displayAllInformation();
		}
		else if ( arg0.getKeyChar() == 'r' || arg0.getKeyChar() == 'R' )
		{
			// re-analyze nodes and show them
			arg0.consume();

			if ( !trackingMode )
			{
				nodes = parent.analyzeNodes( img, currentFrame );
				imp.updateAndDraw();
				displayAllInformation();
			}
		}
		else if ( arg0.getKeyChar() == 'x' )
		{
			// remove all dead ends
			arg0.consume();
			if ( !trackingMode )
			{
				parent.removeAllDeadEnds( img, imp, nodes );
				imp.updateAndDraw();
				displayAllInformation();
			}
		}
		else if ( arg0.getKeyChar() == 'X' )
		{
			arg0.consume();
			
			if ( !trackingMode )
			{
				int current = imp.getFrame();
				for ( int t = 1; t <= imp.getNFrames(); ++t )
				{
					// triggers already updateSource() due to the SliceListener
					imp.setPosition( imp.getStackIndex( imp.getChannel(), imp.getSlice(), t ) );
					parent.removeAllDeadEnds( img, imp, nodes );
				}
				imp.setPosition( imp.getStackIndex( imp.getChannel(), imp.getSlice(), current ) );
				displayAllInformation();
			}
		}
		else if ( arg0.getKeyCode() == 37 )
		{
			arg0.consume();
			if ( imp.getFrame() > 1 )
			{
				imp.setPosition( imp.getStackIndex( imp.getChannel(), imp.getSlice(), imp.getFrame() - 1 ) );
				imp.updateAndDraw();
			}
			updateSource();
		}
		else if ( arg0.getKeyCode() == 39 )
		{
			arg0.consume();
			
			if ( imp.getFrame() < imp.getNFrames() )
			{
				imp.setPosition( imp.getStackIndex( imp.getChannel(), imp.getSlice(), imp.getFrame() + 1 ) );
				imp.updateAndDraw();
			}
			updateSource();
		}
		else if ( arg0.getKeyCode() == 27 ) //ESC
		{
			arg0.consume();
			
			imp.setOverlay( null );
			
			if ( sliceObserver != null )
				sliceObserver.unregister();
			
			this.unregisterTool();
			this.unregisterTool( imp );
			this.unregisterTool( imp.getCanvas() );
		}
		else if ( arg0.getKeyChar() == 'f' || arg0.getKeyChar() == 'F' )
		{
			arg0.consume();
			holdingKeyF = true;
			xd = getXCoordinate();
			yd = getYCoordinate();
		}
		else if ( arg0.getKeyChar() == 't' || arg0.getKeyChar() == 'T' )
		{
			arg0.consume();

			if ( trackingMode )
			{
				trackingMode = false;
				trackingInitialized = TrackingStatus.NOT_INITIALIZED;
				displayAllInformation();
			}
			else
			{
				trackingMode = true;

				for ( int t = 1; t <= imp.getNFrames(); ++t )
					segmentLocationPerFrame[ t - 1 ] = null;

				//for ( int n = 0; n < imp.getNFrames(); ++n )
				//	nodeLocationPerFrame[ n ][ 0 ] = nodeLocationPerFrame[ n ][ 1 ] = -1;
				this.nodes = parent.analyzeNodes( img, currentFrame );
				//this.nodeTree = new KDTree<Node>( nodes, nodes );
				holdingKeyF = false;
				trackingInitialized = TrackingStatus.NOT_INITIALIZED;
				displayAllInformation();
			}
		}
		else if ( arg0.getKeyChar() == '>' )
		{
			arg0.consume();
			
			if ( trackingMode && trackingInitialized != TrackingStatus.NOT_INITIALIZED )
			{
				int current = currentFrame;
				trackForwardThroughTime( current );
				imp.setPosition( imp.getStackIndex( imp.getChannel(), imp.getSlice(), current ) );
			}
		}
		else if ( arg0.getKeyChar() == '<' )
		{
			arg0.consume();
			
			if ( trackingMode && trackingInitialized != TrackingStatus.NOT_INITIALIZED )
			{
				int current = currentFrame;
				trackBackwardThroughTime( current );
				imp.setPosition( imp.getStackIndex( imp.getChannel(), imp.getSlice(), current ) );
			}
		}
		else if ( arg0.getKeyChar() == 'm' || arg0.getKeyChar() == 'M' )
		{
			arg0.consume();
			
			if ( trackingMode && trackingInitialized != TrackingStatus.NOT_INITIALIZED )
			{
				int current = currentFrame;
				measure();
				imp.setPosition( imp.getStackIndex( imp.getChannel(), imp.getSlice(), current ) );
			}
		}
		
		//IJ.log( "pressed " + arg0 );
	}
	
	protected Object measure()
	{
		final RoiManager rm = RoiManagerHandling.getRoiManager();

		for ( int t = 1; t <= imp.getNFrames(); ++t )
		{
			final Segment segment = segmentLocationPerFrame[ t -1 ];
			if ( segment != null )
			{
				imp.setPosition( imp.getChannel(), imp.getSlice(), t );
				rm.addRoi( RoiManagerHandling.createRoi( segment.getPoints(), "t=" + t ) );
			}
		}

		return true;
	}
	
	private static < T extends RealType< T > > float meanIntensity( final Image< T > img, final PartialSegment segment )
	{
		final LocalizableByDimCursor< T > randomAccess = img.createLocalizableByDimCursor();
		
		double sum = 0;
		
		for ( final int[] location : segment.points )
		{
			randomAccess.setPosition( location );
			sum += randomAccess.getType().getRealDouble();
		}
		
		return (float)( sum / segment.points.size() );
	}
	
	private static final float sqDistance( final float[] v1, final float[] v2 )
	{
		float distance = 0;
		
		for ( int d = 0; d < v1.length; ++d )
			distance += ( v2[ d ] - v1[ d ] ) * ( v2[ d ] - v1[ d ] );
		
		return distance;
	}
	
	protected TextRoi getTrackingModeText()
	{
		TextRoi t = new TextRoi( 5, 5, "Tracking Mode On" );
		t.setStrokeColor( Color.green );
		Color col = new Color( 1f, 1f, 1f, 0.5f );
		t.setFillColor( col );
		
		return t;
	}
	
	protected Overlay getTrackingOverlay()
	{
		final Segment segment = segmentLocationPerFrame[ currentFrame - 1 ];

		Overlay o = new Overlay();

		if ( segment == null )
		{
			IJ.log( "There was a problem finding a segment for t=" + currentFrame + ", please add manually." );
			return o;
		}

		OvalRoi o1 = new OvalRoi( segment.getNode1().getPosition()[ 0 ] - 3, segment.getNode1().getPosition()[ 1 ] - 3, 7, 7 );
		o1.setStrokeColor( Color.GREEN );
		o.add( o1 );

		o1 = new OvalRoi( segment.getNode2().getPosition()[ 0 ] - 3, segment.getNode2().getPosition()[ 1 ] - 3, 7, 7 );
		o1.setStrokeColor( Color.GREEN );
		o.add( o1 );

		PolygonRoi roi = segment.getPolygonRoi();
		roi.setStrokeColor( Color.RED );
		o.add( roi );

		o.add( getTrackingModeText() );
		imp.setOverlay( o );
		
		return o;
	}
	
	protected Overlay getAllNodesOverlay()
	{
		Overlay o = new Overlay();
		
		for ( final Node node : nodes )
		{
			OvalRoi oval = new OvalRoi( node.getPosition()[ 0 ] - 1, node.getPosition()[ 1 ] - 1, 3, 3 );
			
			if ( node.numEdges == 1 )
				oval.setStrokeColor( Color.RED );
			else if ( node.numEdges == 2 )
				oval.setStrokeColor( Color.MAGENTA );
			else if ( node.numEdges >= 3 )
				oval.setStrokeColor( Color.GREEN );
			
			o.add( oval );
		}
		
		return o;
	}
	
	public void displayAllInformation()
	{
		if ( trackingMode )
		{
			if ( trackingInitialized == TrackingStatus.FULLY_TRACKED )
				imp.setOverlay( getTrackingOverlay() );
			else if ( trackingInitialized == TrackingStatus.PARTIALLY_TRACKED && segmentLocationPerFrame[ currentFrame - 1 ] != null )
				imp.setOverlay( getTrackingOverlay() );
			else
				imp.setOverlay( new Overlay( getTrackingModeText() ) );
		}
		else
		{
			imp.setOverlay( getAllNodesOverlay() );
		}
	}

	@Override
	final public void mouseDragged( final MouseEvent e ) {}

	@Override
	public void mouseEntered( final MouseEvent arg0 ) {}

	@Override
	public void mouseExited(MouseEvent arg0) {}

	@Override
	public void mousePressed(MouseEvent arg0) {}

	@Override
	public void mouseReleased(MouseEvent arg0) {}

	@Override
	public void keyReleased(KeyEvent arg0) 
	{ 
		if ( arg0.getKeyChar() == 'f' || arg0.getKeyChar() == 'F' )
		{
			arg0.consume();
			holdingKeyF = false;
			xd = yd = -1;
			nodes = parent.analyzeNodes( img, currentFrame );
			imp.updateAndDraw();
			displayAllInformation();
		}
	}

	protected class ImagePlusListener implements SliceListener
	{
		@Override
		public void sliceChanged(ImagePlus arg0)
		{
			if ( currentFrame != imp.getFrame() )
				updateSource();
		}		
	}
	
	@Override
	public void keyTyped(KeyEvent arg0) {}
}
