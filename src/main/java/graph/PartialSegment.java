package graph;

import ij.gui.PointRoi;
import ij.gui.PolygonRoi;
import ij.gui.Roi;

import java.util.ArrayList;

public class PartialSegment 
{
	final ArrayList< int[] > points;
	final Node node1;
	
	public PartialSegment( final ArrayList< int[] > points, final Node node )
	{
		this.node1 = node;
		this.points = points;
	}
	
	public ArrayList< int[] > getPoints() { return points; }
	public Node getNode1() { return node1; }

	/**
	 * @return a {@link PointRoi} covering all points on the segment (except for the nodes)
	 */
	public PointRoi getPointRoi() 
	{		
		int[] px = new int[ points.size() ];
		int[] py = new int[ points.size() ];
		
		for ( int i = 0; i < points.size(); ++i )
		{
			px[ i ] = points.get( i )[ 0 ];
			py[ i ] = points.get( i )[ 1 ];
		}
		
		return new PointRoi( px, py, px.length );
	}
	
	public PolygonRoi getPolygonRoi()
	{
		int[] px = new int[ points.size() ];
		int[] py = new int[ points.size() ];
		
		for ( int i = 0; i < points.size(); ++i )
		{
			px[ i ] = points.get( i )[ 0 ];
			py[ i ] = points.get( i )[ 1 ];
		}

		return new PolygonRoi( px, py, px.length, Roi.POLYLINE );
	}
}
