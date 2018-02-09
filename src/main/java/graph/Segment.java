package graph;

import java.util.ArrayList;

public class Segment extends PartialSegment
{
	final Node node2;
	
	public Segment( final ArrayList< int[] > points, final Node node1, final Node node2 )
	{
		super( points, node1 );
		this.node2 = node2;
	}
	
	public Node getNode2() { return node2; }	
}
