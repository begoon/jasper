/*
 * @(#)Jasper.java 1.1 27/04/97 Adam Davidson & Andrew Pollard
 */
import java.awt.*;
import java.applet.*;
import java.net.*;
import java.io.*;

/**
 * <p>The <a href="http://www.odie.demon.co.uk/spectrum">Jasper</a> class wraps up
 * the Spectrum class into an Applet which emulates a ZX Spectrum in a Web Page.</p>
 *
 * <p><center>
 *   <applet code=Jasper.class width=296 height=232>
 *   <param name=sleepHack value=5>
 *   </applet>
 * </center></p>
 *
 * <p>This applet can be supplied the following parameters:
 * <ul>
 *   <li><b>snapshot   </b> - name of SNA or Z80 snapshot to load
 *   <li><b>rom        </b> - filename of ROM (default=spectrum.rom)
 *   <li><b>borderWidth</b> - width of spectrum border (default=20)
 *   <li><b>refreshRate</b> - refresh screen every 'X' interrupts (default=1)
 *   <li><b>sleepHack  </b> - sleep per interrupt in ms, for old VMs (default=0)
 *   <li><b>showStats  </b> - show progress bar (default=Yes)
 * </ul>
 *
 * <p>The <b>snapshot</b> parameter can specify a file in one of two popuplar formats,
 *    <a href="http://www.nvg.unit.no/sinclair/formats/z80-format.html">.z80</a>
 * or <a href="http://www.nvg.unit.no/sinclair/formats/sna-format.html">.sna</a>.
 * The type is determined at runtime by checking the length of the snapshot
 * file, if it is 49,179 then it is assumed to be of type .sna else it is assumed to
 * be of type .z80.</p>
 *
 * <p>The <b>rom</b> parameter can be used to specify a different ROM to load
 * other than the standard sinclair ROM. This file must be 16,384 bytes in length.
 * The default value is <b>spectrum.rom</b>.</p>
 *
 * <p>The <b>borderWidth</b> parameter specifies the width in pixels of the border
 * which surrounds the main screen. The default value is <b>20</b>.</p>
 *
 * <p>The <b>refreshRate</b> parameter specifies how often the screen should be
 * updated in terms of Z80 interrupts. The default value is <b>1</b>.</p>
 *
 * <p>The <b>sleepHack</b> parameter is needed to introduce a delay into the main
 * Z80 execute loop. In some browsers, the thread which updates the screen and
 * delivers keyboard/mouse events to the applet can be blocked out by the Spectrum
 * thread since it never gives up its timeslice. By default the value for this
 * is <b>0</b> which means that the main execute loop runs at full speed.
 * 
 * <p>The <b>showStats</b> parameter enables or disables the progress bar at the
 * foot of the screen. The values that can be supplied are <b>Yes</b> and <b>No</b>.
 * The default value is <b>Yes</b>.</p>
 *
 * <p>Since the release of version 1.0 Jasper has won various awards:</p>
 *
 * <center>
 * <A HREF="http://www.jars.com/">
 *   <IMG SRC="http://www.jars.com/images/1perc.gif" BORDER="0" height="95" width="115" ALT="Rated Top 1% WebApplet by JARS"></A>
 * <A HREF="http://www.gamelan.com/">
 *   <IMG SRC="http://www.gamelan.com/stickers/coolbutton.gif" alt="Gamelan" height=43 width=197></A>
 * <a href="http://www.java.co.uk/">
 *   <img src="http://www.java.co.uk/JC/images/winner.gif" alt="Duke Award" align=middle width=39 height=64 border=0></a>
 * </center>
 *
 * <p>Jasper is best viewed with:<br>
 * <center>
 * <A HREF="http://www.microsoft.com/ie/ie.htm">
 *   <IMG SRC="http://www.microsoft.com/ie/images/logos/ie_animated.gif" ALT="Download Explorer" ALIGN=BOTTOM BORDER=0 VSPACE=7 WIDTH=88 HEIGHT=31 HSPACE=5></A></p>
 * </center>
 *
 * <p>Thanks to <A HREF="http://www.cs.brown.edu/people/amd/">Adam Doppelt</A>
 * for his AMDProgressBar class.</p>
 *
 * <b>Version:</b> 1.1 27 Apr 1997<br>
 * <b>Authors:</b> <A HREF="http://www.odie.demon.co.uk/spectrum">Adam Davidson & Andrew Pollard</A><br>
 *
 * @see Spectrum
 * @see Z80
 */

public class Jasper extends Applet implements Runnable {
	Spectrum    spectrum = null;
	Thread      thread   = null;

	/** Version and author information. */
	public String getAppletInfo() {
		return "Jasper V1.1 by Adam Davidson and Andrew Pollard";
	}

	/** Applet parameters and their descriptions. */
	public String[][] getParameterInfo() {
		String [][] info = {
			{ "snapshot",    "filename", "name of SNA or Z80 snapshot to load" },
			{ "rom",         "filename", "filename of ROM (default=spectrum.rom)" },
			{ "borderWidth", "integer",  "width of spectrum border (default=20)" },
			{ "refreshRate", "integer",  "refresh screen every 'X' interrupts (default=1)" },
			{ "sleepHack",   "integer",  "sleep per interrupt in ms, for old VMs (default=0)" },
			{ "showStats",   "Yes/No",   "show progress bar (default=Yes)" }, 
		};
		return info;
	}

	/** Initailize the applet. */
	public void init() {
		setLayout( null );
	}

	/** Start the applet creating a new thread which invokes run(). */
	public void start() {
		if ( thread == null ) {
			thread = new Thread( this, "Jasper" );
			thread.start();
		}
	}

	/** Stop the applet. */
	public void stop() {
		if ( thread != null ) {
			thread.stop();
			thread = null;
		}
	}

	/** Parse available applet parameters.
	 *  @exception Exception Problem loading ROM or snaphot.
	 */
	public void readParameters() throws Exception {
		String rom = getParameter( "rom" );
		if ( rom == null ) {
			rom = "spectrum.rom";
		}

		spectrum.setBorderWidth( getIntParameter( "borderWidth",
			spectrum.borderWidth*spectrum.pixelScale, 0, 100 ) );
			
		spectrum.refreshRate   = getIntParameter( "refreshRate",
			spectrum.refreshRate, 1, 100 );

		spectrum.sleepHack   = getIntParameter( "sleepHack",
			spectrum.sleepHack, 0, 100 );


		resize( preferredSize() ); // once borderWidth is set up

		String showStats = getParameter( "showStats" );
		if ( showStats != null ) {
			if ( showStats.equals( "Yes" ) ) {
				spectrum.showStats = true;
			}
			else
			if ( showStats.equals( "No" ) ) {
				spectrum.showStats = false;
			}
		}

		URL baseURL = getDocumentBase();
		spectrum.urlField.setText( baseURL.toString() );

		URL romURL = new URL( baseURL, rom );
		spectrum.loadROM( romURL.toString(), romURL.openStream() );

            	String snapshot = getParameter( "snapshot" );
		snapshot = ((snapshot == null) ? getParameter( "sna" ) : snapshot);
		snapshot = ((snapshot == null) ? getParameter( "z80" ) : snapshot);
            	if ( snapshot != null ) {
	  		URL	          url  = new URL( baseURL, snapshot );
			URLConnection snap = url.openConnection();

			InputStream input = snap.getInputStream();
			spectrum.loadSnapshot( url.toString(), input, snap.getContentLength() );
			input.close();
		}
		else {
			spectrum.reset();
			spectrum.refreshWholeScreen();
		}
	}

	/** Read applet parameters and start the Spectrum. */
	public void run() {
		showStatus( getAppletInfo() );

		if ( spectrum == null ) {
			try {
           			spectrum = new Spectrum( this );
				readParameters();
			}
			catch ( Exception e ) {
				showStatus( "Caught IO Error: " + e.toString() );
			}
		}

		if ( spectrum != null ) {
			spectrum.execute();
		}
	}

	/** Handle integer parameters in a range with defaults. */
	public int getIntParameter( String name, int ifUndef, int min, int max ) {
		String param = getParameter( name );
		if ( param == null ) {
			return ifUndef;
		}

		try {
			int n = Integer.parseInt( param );
			if ( n < min ) return min;
			if ( n > max ) return max;
			return n;
		}
		catch ( Exception e ) {
			return ifUndef;
		}
	}

	/** Refresh handling. */
	public void update( Graphics g ) {
		paint( g );
	}

	/** Paint sets a flag on the spectrum to tell it to redraw the
	    screen on the next Z80 interrupt. */
	public void paint( Graphics g ) {
		if ( spectrum != null ) {
			spectrum.repaint();
		}
	}

	/** Event handling. */
	public boolean handleEvent( Event e ) {
		if ( spectrum != null ) {
			return spectrum.handleEvent( e );
		}
		return super.handleEvent( e );
	}

	/** Applet size. */
	public Dimension minimumSize() {
		int scale  = spectrum.pixelScale;
		int border = (spectrum == null) ? 20 : spectrum.borderWidth;

		return new Dimension(
			spectrum.nPixelsWide * scale + border*2,
			spectrum.nPixelsHigh * scale + border*2
		);
	}

	/** Returns Jasper.minimumSize(). */
	public Dimension preferredSize() {
		return minimumSize();
	}
}
