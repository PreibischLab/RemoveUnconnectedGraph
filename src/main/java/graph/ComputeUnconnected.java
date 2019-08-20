package graph;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;

import ij.CompositeImage;
import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.gui.OvalRoi;
import ij.gui.Roi;
import ij.plugin.PlugIn;
import ij.plugin.frame.RoiManager;
import mpicbg.imglib.container.array.ArrayContainerFactory;
import mpicbg.imglib.cursor.LocalizableByDimCursor;
import mpicbg.imglib.cursor.LocalizableCursor;
import mpicbg.imglib.cursor.special.LocalNeighborhoodCursor;
import mpicbg.imglib.image.Image;
import mpicbg.imglib.image.ImageFactory;
import mpicbg.imglib.image.display.imagej.ImageJFunctions;
import mpicbg.imglib.io.ImageOpener;
import mpicbg.imglib.outofbounds.OutOfBoundsStrategyValueFactory;
import mpicbg.imglib.type.numeric.RealType;
import mpicbg.imglib.type.numeric.integer.UnsignedByteType;
import mpicbg.imglib.type.numeric.real.FloatType;
import mpicbg.imglib.util.Util;
import net.imglib2.KDTree;
import net.imglib2.Localizable;
import net.imglib2.RealLocalizable;
import net.imglib2.neighborsearch.KNearestNeighborSearch;
import net.imglib2.neighborsearch.KNearestNeighborSearchOnKDTree;
import net.imglib2.neighborsearch.NearestNeighborSearch;
import net.imglib2.neighborsearch.NearestNeighborSearchOnKDTree;

public class ComputeUnconnected implements PlugIn
{
	public static int defaulChannelChoice = 1;
	
	@Override
	public void run( String s )
	{
		ImagePlus imp = WindowManager.getCurrentImage();
		
		if ( imp.getNSlices() > 1 || imp.getType() == ImagePlus.COLOR_RGB || imp.getType() == ImagePlus.COLOR_256 )
		{
			IJ.log( "This plugin does not work with stacks, timepoints, RGB or 8-bit Color, only with (multichannel composite) 2-d images." );
			return;
		}
		
		if ( imp.getNChannels() > 1 )
		{
			final int numChannels = imp.getNChannels();
			final String[] channels = new String[ numChannels ];
			for ( int i = 0; i < numChannels; ++i )
				channels[ i ] = "" + (i+1);
			
			GenericDialog gd = new GenericDialog( "Select channel" );
						
			if ( defaulChannelChoice >= numChannels )
				defaulChannelChoice = 0;
			
			gd.addChoice( "Binary channel with segementation", channels, channels[ defaulChannelChoice ] );
			gd.showDialog();
			
			if ( gd.wasCanceled() )
				return;
			
			defaulChannelChoice = gd.getNextChoiceIndex();
			
			// wrap just the channel we are interested it
			interactiveRemoval( imp, defaulChannelChoice );
		}
		else
		{
			interactiveRemoval( imp, -1 );
		}
	}
	
	public void loadFileAndStart( final String fileName )
	{
		Image< FloatType > tmp = open( fileName );
		tmp.getDisplay().setMinMax();
		
		final ImagePlus imp = ImageJFunctions.copyToImagePlus( tmp );
		
		if ( imp.getNSlices() > 1 && imp.getNFrames() == 1 )
			imp.setStack( imp.getImageStack(), 1, 1, imp.getNSlices() );

		interactiveRemoval( imp, -1 );
	}

	public < T extends RealType< T > > void interactiveRemoval( ImagePlus imp, final int channel )
	{
		imp.show();
		new MouseEventHandler< T >( imp, channel, this );
	}
	
	public < T extends RealType< T > > ArrayList< Node > analyzeNodes( final Image< T > img, final int frame )
	{
		removeRedundantPixels( img, frame );
		
		final ArrayList< Node > nodes = findAllNodes( img, frame );
		
		//printNodeStatistics( nodes );
		//drawNodes( nodes, img.getDimensions() );
		
		return nodes;
	}
	
	public Node findClosest3WayNode( final KDTree< Node > nodes, final RealLocalizable location )
	{
		if ( nodes == null )
			return null;
		
		NearestNeighborSearch< Node > search = new NearestNeighborSearchOnKDTree<Node>( nodes );
		search.search( location );
		
		if ( search.getSampler().get().numEdges == 3 )
		{
			return search.getSampler().get();
		}
		else
		{
			for ( int i = 5; i < 100; i += 10 )
			{
				KNearestNeighborSearch< Node > s2 = new KNearestNeighborSearchOnKDTree<Node>( nodes, i );
				s2.search( location );
				
				for ( int n = 0; n <= i; ++n )
				{
					Node node = s2.getSampler( n ).get();
					if ( node.numEdges == 3 )
						return node;
				}
			}
			return null;
		}
	}

	public < T extends RealType< T > > void removeAllDeadEnds( final Image< T > img, final ImagePlus imp, final ArrayList< Node > nodes )
	{
		// create a list of dead ends
		final ArrayList< int[] > deadEnds = new ArrayList< int[] >();
		
		for ( final Node node : nodes )
			if ( node.numEdges == 1 )
				deadEnds.add( node.getPosition().clone() );
		
		boolean removedDeadEnd = false;
		
		do
		{
			removedDeadEnd = false;
			
			for ( int i = 0; i < nodes.size(); ++i )
			{
				final Node node = nodes.get( i );

				// is it a dead end?
				if ( node.numEdges == 1 && contains( node.getPosition(), deadEnds ) )
				{
					LocalizableByDimCursor< T > randomAccess = img.createLocalizableByDimCursor( new OutOfBoundsStrategyValueFactory< T >() );
					randomAccess.setPosition( node.getPosition() );
					
					// go from the dead end to the next node
					PartialSegment p = findPathToNextNode( randomAccess, nodes, 0, true );
					
					if ( p == null )
					{
						nodes.clear();
						nodes.addAll( analyzeNodes( img, imp.getFrame() ) );
						imp.updateAndDraw();
						
						// found a bug in the graph
						IJ.log( "There is a bug in the graph starting from " + Util.printCoordinates( node.getPosition() ) + ", re-analyzing the image" );
						
						randomAccess = img.createLocalizableByDimCursor( new OutOfBoundsStrategyValueFactory< T >() );
						randomAccess.setPosition( node.getPosition() );
						p = findPathToNextNode( randomAccess, nodes, 0, true );
					}
					
					// delete all pixels on the path
					for ( final int[] location : p.getPoints() )
					{
						randomAccess.setPosition( location );
						randomAccess.getType().setZero();
					}
					
					// reduce/remove the node we started with
					reduceNode( node, randomAccess, nodes );
					
					// and the one we end up at
					reduceNode( p.getNode1(), randomAccess, nodes );
					
					removedDeadEnd = true;
					break;
				}
			}		
		} 
		while ( removedDeadEnd );
	}
	
	public boolean contains( final int[] location, final ArrayList< int[] > locations )
	{
		for ( final int[] l : locations )
			if ( isIdentical( location, l ) )
				return true;
		
		return false;
	}
	
	/**
	 * change the nodes to one connection less
	 * 
	 * @param node
	 * @param randomAccess
	 * @param nodes
	 */
	public < T extends RealType< T > > void reduceNode( final Node node, final LocalizableByDimCursor< T > randomAccess, final ArrayList< Node > nodes )
	{	
		if ( node.numEdges == 1 )
		{
			// it was a dead end, delete it
			nodes.remove( node );
			
			randomAccess.setPosition( node.location );
			randomAccess.getType().setZero();
		}
		else
		{
			// it has one connection less
			node.numEdges--;
		}	
	}
	
	public < T extends RealType< T > > ArrayList< PartialSegment > findAllSegments( final Image< T > img, final KDTree< Node > nodes, final ArrayList< Node > nodeList, final Node centralNode )
	{
		if ( centralNode == null )
			return null;
		
		final ArrayList< PartialSegment > segments = new ArrayList< PartialSegment >( centralNode.numEdges );
		
		for ( int i = 0; i < centralNode.numEdges; ++i )
		{
			LocalizableByDimCursor< T > randomAccess = img.createLocalizableByDimCursor( new OutOfBoundsStrategyValueFactory< T >() );
			randomAccess.setPosition( centralNode.getPosition() );
	
			PartialSegment s = findPathToNextNode( randomAccess, nodeList, i, true );
			
			if ( s == null )
				return null;
			else
				segments.add( s );
		}

		return segments;
	}
	
	/**
	 * Find the two nodes connected by this segment and all points on the connecting line (excluding the nodes). 
	 * This is still inefficient, doing this with a KD-Tree would be more efficient.
	 * 
	 * @param img
	 * @param nodes
	 * @param x0
	 * @param y0
	 */
	public < T extends RealType< T > > Segment findSegment( final Image< T > img, final ArrayList< Node > nodes, final int[] start )
	{
		LocalizableByDimCursor< T > randomAccess = img.createLocalizableByDimCursor( new OutOfBoundsStrategyValueFactory< T >() );
		randomAccess.setPosition( start );
		
		PartialSegment s1 = findPathToNextNode( randomAccess, nodes, 0 );
		
		randomAccess = img.createLocalizableByDimCursor( new OutOfBoundsStrategyValueFactory< T >() );
		randomAccess.setPosition( start );
		
		PartialSegment s2 = findPathToNextNode( randomAccess, nodes, 1 );

		if ( s1 == null || s2 == null )
		{
			// found a bug in the graph
			IJ.log( "There is a bug in the graph starting from " + Util.printCoordinates( start ) + ", please re-analyze the image (press 'r')" );
			return null;
		}

		// is the location we clicked on a node itself?
		if ( s1.getNode1() == s2.getNode1() && s1.getPoints().size() == 0 && s2.getPoints().size() == 0 )
			return null;
		
		// else build up a list of consequetive points
		final ArrayList< int[] > points = new ArrayList< int[] >();
		
		// in reverse order the first points
		for ( int i = s1.getPoints().size() - 1; i >= 0; --i )
			points.add( s1.getPoints().get( i ) );
		
		// now the center point that was clicked
		points.add( start );
		
		// and all the other points from partial segment 2
		for ( int i = 0; i < s2.getPoints().size(); ++i )
			points.add( s2.getPoints().get( i ) );

		return new Segment( points, s1.getNode1(), s2.getNode1() );
	}

	/**
	 * Finds the path to the next node from a certain location on a line (excluding the current position and the node itself).
	 * If the point itself is a node or the directly adjacent one, the list of points will be empty.
	 * 
	 * @param randomAccess
	 * @param nodes
	 * @param startDirection
	 * @return
	 */
	public < T extends RealType< T > > PartialSegment findPathToNextNode( final LocalizableByDimCursor< T > randomAccess, final ArrayList< Node > nodes, final int startDirection )
	{
		return findPathToNextNode( randomAccess, nodes, startDirection, false );
	}
	
	public < T extends RealType< T > > PartialSegment findPathToNextNode( final LocalizableByDimCursor< T > randomAccess, final ArrayList< Node > nodes, final int startDirection, final boolean ignoreDeadEnds )
	{
		final ArrayList< int[] > points = new ArrayList< int[] >();
		Node node = isNode( randomAccess.getPosition(), nodes );
		
		if ( node != null && !ignoreDeadEnds )
				return new PartialSegment( points, node );
	
		final int[] startLocation = randomAccess.getPosition();
		
		final LocalNeighborhoodCursor< T > neighborhoodCursor = randomAccess.createLocalNeighborhoodCursor();
		
		int[] currentPosition = randomAccess.getPosition();
		int[] nextPosition = null;
		
		int count = 0;
		
		while ( neighborhoodCursor.hasNext() )
		{
			neighborhoodCursor.fwd();
			
			if ( neighborhoodCursor.getType().getRealFloat() > 0 )
			{
				if ( startDirection == count++ )
				{
					nextPosition = randomAccess.getPosition();
					node = isNode( nextPosition, nodes );
					
					break;
				}
			}
		}
		
		// startDirection does not exist
		if ( nextPosition == null )
		{
			neighborhoodCursor.close();
			return new PartialSegment( points, node );
		}
		
		// follow the path until a node is reached
		while ( node == null )
		{
			randomAccess.setPosition( nextPosition );
			neighborhoodCursor.update();
			
			// did we arrive back where we started?
			// (is it a closed loop without nodes?)
			if ( isIdentical( nextPosition, startLocation ) )
			{
				// add an artifical node at the current location
				node = new Node( nextPosition, 2 );
				return new PartialSegment( points, node );
			}
			
			points.add( nextPosition );
			
			// find all possible points to continue (except for the one that we come from)
			final ArrayList< int[] > connections = getAllNeighbors( neighborhoodCursor, currentPosition );
			
			if ( connections.size() == 0 )
			{
				// a bug in the graph, did not find a node at the end
				return null;
			}
			if ( connections.size() == 1 )
			{
				currentPosition = nextPosition;
				nextPosition = connections.get( 0 );

				// is the next one a node?
				node = isNode( nextPosition, nodes );
			}
			else
			{
				// one of them has to be a node, find the one that is
				for ( final int[] next : connections )
				{
					node = isNode( next, nodes );
					
					if ( node != null )
						break;
				}
				
				// there was something wrong, none of them was a node
				if ( node == null && !ignoreDeadEnds )
					return null;
				else
				{
					// we have to check if the previous one was a node, if so it is ok
					Node n = isNode( currentPosition, nodes );
					
					if ( n != null && n.numEdges == 3 )
					{
						// choose the one that has a distance of 2 from the node
						for ( final int[] next : connections )
						{
							final int dist = Math.max( Math.abs( next[ 0 ] - currentPosition[ 0 ] ), Math.abs( next[ 1 ] - currentPosition[ 1 ] ) );
							if ( dist >= 2 )
							{
								currentPosition = nextPosition;
								nextPosition = next;

								// is the next one a node?
								node = isNode( nextPosition, nodes );
								break;
							}
						}
					}
				}
			}
		}
		
		neighborhoodCursor.close();
		
		return new PartialSegment( points, node );
	}
	
	public static < T extends RealType< T > > ArrayList< int[] > getAllNeighbors( final LocalNeighborhoodCursor< T > cursor, int[] exclude )
	{
		final ArrayList< int[] > list = new ArrayList<int[]>();
		
		while ( cursor.hasNext() )
		{
			cursor.fwd();
			
			if ( cursor.getType().getRealFloat() > 0 )
				if ( !isIdentical( exclude, cursor.getParentCursor().getPosition() ) )
					list.add( cursor.getParentCursor().getPosition() );
		}
		
		return list;
	}
	
	public static boolean isIdentical( final int[] p1, final int[] p2 )
	{
		for ( int d = 0; d < p1.length; ++d )
			if ( p1[ d ] != p2[ d ] )
				return false;
		
		return true;
	}
	
	public Node isNode( final int[] position, final ArrayList< Node > nodes )
	{
		for ( final Node node : nodes )
			if ( position[ 0 ] == node.getPosition()[ 0 ] && position[ 1 ] == node.getPosition()[ 1 ] )
				return node;
		
		return null;
	}

	public Node isNode( final Localizable position, final NearestNeighborSearch< Node > nodeSearch )
	{
		nodeSearch.search( position );
		final Node node = nodeSearch.getSampler().get();
		
		if ( node == null )
			return null;
		
		for ( int d = 0; d < position.numDimensions(); ++d )
			if ( node.getPosition()[ d ] != position.getIntPosition( d ) )
				return null;
		
		return node;
	}

	public < T extends RealType< T > > int[] findClosestPointOnPath( final Image< T > img, final int x0, final int y0, final ArrayList< Node > nodes )
	{
		final LocalizableByDimCursor< T > randomAccess = img.createLocalizableByDimCursor( new OutOfBoundsStrategyValueFactory< T >() );
		
		randomAccess.setPosition( x0, 0 );
		randomAccess.setPosition( y0, 1 );
		
		final LocalizableByDimCursor< T > localCursor = img.createLocalizableByDimCursor( new OutOfBoundsStrategyValueFactory< T >() );
		final LocalNeighborhoodCursor< T > neighborhoodCursor = localCursor.createLocalNeighborhoodCursor();

		for ( int r = 0; r <= 5; ++r )
			for ( int x = x0 - r; x <= x0 + r; ++x )
				for ( int y = y0 - r; y <= y0 + r; ++y )
				{
					randomAccess.setPosition( x, 0 );
					randomAccess.setPosition( y, 1 );
				
					if ( randomAccess.getType().getRealFloat() > 0 )
					{
						// test that it has exactly two neighbors in the 8-neighborhood,
						// otherwise it is a point of interest itself (which we do not want
						localCursor.setPosition( randomAccess );
						neighborhoodCursor.update();
						
						int numNeighbors = 0;

						while ( neighborhoodCursor.hasNext() )
						{
							neighborhoodCursor.fwd();
							if ( neighborhoodCursor.getType().getRealFloat() > 0 )
								numNeighbors++;
						}

						// if two neighbors return the position
						// also check that it is not one of the left-over nodes which also has two neighbors 
						if ( numNeighbors == 2 && null == isNode( randomAccess.getPosition(), nodes ) )
							return randomAccess.getPosition();
						
						// else continue searching on the next position
					}
				}
		
		return null;
	}
	
	/**
	 * Remove all pixels that are redundant if they are still connected by 8-neighborhood 
	 * 
	 * @param image
	 */
	public < T extends RealType< T > > void removeRedundantPixels( final Image< T > img, final int frame )
	{
		removeSpecialCase( img );

		final LocalizableCursor< T > cursor = img.createLocalizableCursor();
		final LocalizableByDimCursor< T > localCursor = img.createLocalizableByDimCursor( new OutOfBoundsStrategyValueFactory< T >() );
		final LocalNeighborhoodCursor< T > neighborhoodCursor = localCursor.createLocalNeighborhoodCursor();

		int countRemoved = 0;
		
		while( cursor.hasNext() )
		{
			cursor.fwd();
			
			// is intensity > 0?
			if ( cursor.getType().getRealFloat() > 0 )
			{
				localCursor.setPosition( cursor );
				neighborhoodCursor.update();

				final ArrayList<int[]> neighbors = new ArrayList<int[]>();
				
				// how many 8-connected neighboring pixels are >0?
				while ( neighborhoodCursor.hasNext() )
				{
					neighborhoodCursor.fwd();
					
					if ( neighborhoodCursor.getType().getRealFloat() > 0 )
						neighbors.add( localCursor.getPosition() );
				}
				
				// only if it has at least two neighboring pixels we can test if they are
				// still 8-connected if we remove the current one
				if ( neighbors.size() >= 2 )
				{
					// the remaining pixels must still be conntected through 8-neighborhood
					// if we can iteratively add all pixels to the connected list,
					// they are all directly or indirectly connected once the central pixel
					// is removed
					final ArrayList<int[]> connected = new ArrayList<int[]>();
					
					// add the first one
					connected.add( neighbors.get( 0 ) );
					neighbors.remove( 0 );

					boolean added = false;
					do
					{
						added = false;
						
						// is any of the pixels
A:						for ( final int[] p1 : neighbors )
						{
							// 8-connected to any of the pixels in the connected list?
							for ( final int[] p2 : connected )
							{
								if ( is8connected( p1, p2 ) )
								{
									connected.add( p1 );
									neighbors.remove( p1 );
									added = true;
									break A;
								}
							}
						}
					} while ( added );
					
					// if we managed to put all pixels into the connected list
					// we can remove the current pixel from the image
					
					if ( neighbors.size() == 0 )
					{
						cursor.getType().setZero();
						++countRemoved;
					}
				}
			}
		}
	
		if ( countRemoved > 0 )
			IJ.log( "Removed " + countRemoved + " redundant pixels in frame " + frame );
	}
	
	public static < T extends RealType< T > > boolean removeSpecialCase( final Image< T > img )
	{
		// we have to test for a special case which looks like this
		
		//   x  x        x  x
 		//    xx          xx
		//    xx     -->  x x
		//   x  x        x  x
		int count = 0;
		
		final LocalizableCursor< T > cursor = img.createLocalizableCursor();
		final LocalizableByDimCursor< T > randomAccess = img.createLocalizableByDimCursor( new OutOfBoundsStrategyValueFactory< T >() );
		
		while( cursor.hasNext() )
		{
			if ( cursor.next().getRealFloat() > 0 )
			{
				boolean specialCase = true;
				
				randomAccess.setPosition( cursor );
				
				randomAccess.bck( 0 );
				specialCase &= randomAccess.getType().getRealFloat() > 0;
				randomAccess.bck( 1 );
				specialCase &= randomAccess.getType().getRealFloat() > 0;
				randomAccess.fwd( 0 );
				specialCase &= randomAccess.getType().getRealFloat() > 0;
				
				if ( specialCase )
				{
					++count;
					randomAccess.fwd( 0 );
					randomAccess.fwd( 1 );
					
					if ( randomAccess.getPosition( 0 ) < img.getDimension( 0 ) )
						randomAccess.getType().set( cursor.getType() );
					
					cursor.getType().setZero();
				}
			}
		}

		if ( count > 0 )
			IJ.log( "Removed " + count + " special cases." );
		
		return count > 0;
	}
	
	/**
	 * Find all nodes in an {@link Image}. We consider a node when it has more than 2 neighbors (which is a fork),
	 * just one neighbor which corresponds to a dead end or 0 neighbors which is an isolated pixel.
	 * 
	 * @param img
	 * @return
	 */
	public < T extends RealType< T > > ArrayList< Node > findAllNodes( final Image< T > img, final int frame )
	{
		final ArrayList< Node > nodes = new ArrayList<Node>();
		
		final LocalizableCursor< T > cursor = img.createLocalizableCursor();
		final LocalizableByDimCursor< T > localCursor = img.createLocalizableByDimCursor( new OutOfBoundsStrategyValueFactory< T >() );
		final LocalNeighborhoodCursor< T > neighborhoodCursor = localCursor.createLocalNeighborhoodCursor();
		
		int countRemoved = 0;
		
		while( cursor.hasNext() )
		{
			cursor.fwd();
			
			// is intensity > 0?
			if ( cursor.getType().getRealFloat() > 0 )
			{
				localCursor.setPosition( cursor );
				neighborhoodCursor.update();
				
				final ArrayList<int[]> neighbors = new ArrayList<int[]>();
				
				// how many 8-connected neighboring pixels are >0?
				while ( neighborhoodCursor.hasNext() )
				{
					neighborhoodCursor.fwd();
					
					if ( neighborhoodCursor.getType().getRealFloat() > 0 )
						neighbors.add( localCursor.getPosition() );
				}
				
				int numEdges = neighbors.size();
				
				if ( numEdges == 0 )
				{
					// delete isolated pixels
					cursor.getType().setZero();
					countRemoved++;
				}
				if ( numEdges == 1 )
				{
					// it is a dead end
					nodes.add( new Node( cursor.getPosition(), numEdges ) );
				}
				else if ( numEdges > 2 )
				{
					// it is a junction, test if the neighbors are 4-connected
					// if they are we are they count only as one as it has to be
					// a line that is hit.
					
					// all combinations test only p1 => p2, NOT also p2 => p1!
					for ( int i = 0; i < neighbors.size() - 1; ++i )
						for ( int j = i + 1; j < neighbors.size(); ++j )
							if ( is4connected( neighbors.get( i ), neighbors.get( j ) ) )
								--numEdges;

					// if there are still enough edges touching, add it to the node list
					if ( numEdges > 2 )
						nodes.add( new Node( cursor.getPosition(), numEdges ) );
				}
			}
		}
		
		if ( countRemoved > 0 )
			IJ.log( "Removed " + countRemoved + " isolated pixels in frame " + frame );
		
		return nodes;
	}
	
	/**
	 * Tests if two points are 8-connected
	 * 
	 * @param p1
	 * @param p2
	 * @return - true if 8-connected (or if p1 == p2) 
	 */
	private static boolean is8connected( final int[] p1, final int[] p2 )
	{
		int maxDist = 0;
		
		for ( int d = 0; d < p1.length; ++d )
			maxDist = Math.max( Math.abs( p1[ d ] - p2[ d ] ), maxDist );
		
		return maxDist < 2;	
	}

	/**
	 * Tests if two points are 4-connected
	 * 
	 * @param p1
	 * @param p2
	 * @return - true if 4-connected (or if p1 == p2) 
	 */
	private static boolean is4connected( final int[] p1, final int[] p2 )
	{
		int sumDist = 0;
		
		for ( int d = 0; d < p1.length; ++d )
			sumDist += Math.abs( p1[ d ] - p2[ d ] );
		
		return sumDist < 2;	
	}
	
	public void drawNodes( final ArrayList<Node> nodes, final int[] imgSize ) 
	{
		Image< UnsignedByteType > nodeImg = new ImageFactory< UnsignedByteType >( new UnsignedByteType(), new ArrayContainerFactory() ).createImage( imgSize );

		final LocalizableByDimCursor< UnsignedByteType > c = nodeImg.createLocalizableByDimCursor();
		
		for ( final Node n : nodes )
		{
			c.setPosition( n.location );
			c.getType().set( n.numEdges );
		}

		ImageJFunctions.show( nodeImg );
	}

	public void printNodeStatistics( final Collection< Node > nodes )
	{
		int maxNumEdges = 0;
		
		for ( final Node n : nodes )
			if ( n.numEdges > maxNumEdges )
				maxNumEdges = n.numEdges;
		
		final int[] count = new int[ Math.max( 3, maxNumEdges + 1 ) ];
		
		for ( final Node n : nodes )
			++count[ n.numEdges ];
		
		IJ.log( "Dead-end pixels: " + count[ 1 ] );
		IJ.log( "Left-over forks: " + count[ 2 ] );
		
		for ( int i = 3; i < count.length; ++i )
			IJ.log( i + "-way fork pixels: " + count[ i ] );
	}
	
	public Image< FloatType > open( final String file )
	{
		try 
		{
			return new ImageOpener().openImage( file, new ImageFactory<FloatType>( new FloatType(),  new ArrayContainerFactory() ) );
		}
		catch ( Exception e )
		{
			e.printStackTrace();
			return null;
		}
	}
	
	public static void main( String[] args )
	{
		new ImageJ();

		final CompositeImage c = new CompositeImage( new ImagePlus( new File( "cell1__test_1-250.tif" ).getAbsolutePath() ), CompositeImage.COMPOSITE );
		c.setDimensions( 3, 1, c.getStackSize() / 3 );
		c.show();

		new ComputeUnconnected().interactiveRemoval( c, 1 );

		//new ComputeUnconnected().loadFileAndStart( "/Users/preibischs/Desktop/kuba3.tif" );
		//new ComputeUnconnected().loadFileAndStart( "/Users/preibischs/Desktop/C2-segmentation_manual.tif" );
		//new ComputeUnconnected().loadFileAndStart( "/Users/preibischs/Documents/Microscopy/tissue cells/C2-rigid-common-3.tif" );
	}
}
