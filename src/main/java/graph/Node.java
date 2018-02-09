package graph;

import java.util.ArrayList;

import net.imglib2.RealLocalizable;


public class Node implements RealLocalizable 
{
	final int[] location;
	int numEdges;
	
	final ArrayList< Node > connections;
	
	public Node( final int[] location, final int numEdges )
	{
		this.location = location;
		this.numEdges = numEdges;
		
		this.connections = new ArrayList< Node >();
	}
	
	public int[] getPosition() { return location; }

	@Override
	public int numDimensions() { return location.length; }

	@Override
	public double getDoublePosition( final int d ) { return location[ d ]; }

	@Override
	public float getFloatPosition( final int d ) { return location[ d ]; }

	@Override
	public void localize( final float[] l )
	{
		for ( int d = 0; d < numDimensions(); ++d )
			l[ d ] = location[ d ];
	}

	@Override
	public void localize( final double[] l )
	{
		for ( int d = 0; d < numDimensions(); ++d )
			l[ d ] = location[ d ];
	}
}
