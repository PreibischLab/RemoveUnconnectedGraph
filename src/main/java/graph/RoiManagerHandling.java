package graph;

import java.util.ArrayList;

import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.plugin.frame.RoiManager;

public class RoiManagerHandling
{
	public static RoiManager getRoiManager()
	{
		return new RoiManager();
	}

	/**
	 * This assumes that the points are in order!
	 * 
	 * @param points
	 * @param name
	 * @return
	 */
	public static PolygonRoi createRoi( final ArrayList< int[] > points, final String name )
	{
		final int[] xPoints = new int[ points.size() ];
		final int[] yPoints = new int[ points.size() ];

		for ( int i = 0; i < points.size(); ++i )
		{
			xPoints[ i ] = points.get( i )[ 0 ];
			yPoints[ i ] = points.get( i )[ 1 ];
		}

		final PolygonRoi roi = new PolygonRoi( xPoints, yPoints, points.size(), Roi.FREELINE );
		roi.setName( name );

		return roi;
	}
}
