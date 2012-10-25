/*
 * @(#)Spectrum.java 1.1 27/04/97 Adam Davidson & Andrew Pollard
 */
import java.awt.*;
import java.util.*;
import java.io.*;
import java.net.*;

/**
 * The Spectrum class extends the Z80 class implementing the supporting
 * hardware emulation which was specific to the ZX Spectrum. This
 * includes the memory mapped screen and the IO ports which were used
 * to read the keyboard, change the border color and turn the speaker
 * on/off. There is no sound support in this version.<P>
 *
 *
 * @version 1.1 27 Apr 1997
 * @author <A HREF="http://www.odie.demon.co.uk/spectrum">Adam Davidson & Andrew Pollard</A>
 *
 * @see Jasper
 * @see Z80
 */

public class Spectrum extends Z80 {
	public Graphics		parentGraphics = null;
	public Graphics		canvasGraphics = null;
	public Graphics		bufferGraphics = null;
	public Image		bufferImage = null;

	public Container	parent      = null; // SpecApp usually (where border is drawn)
	public Canvas		canvas      = null; // Main screen
	public TextField	urlField    = null; // ESC shows a URL popup
	public AMDProgressBar	progressBar = null; // How much loaded/how fast?

	public Spectrum( Container _parent ) throws Exception {
		// Spectrum runs at 3.5Mhz
		super( 3.5 );

		parent = _parent;

		parent.add( canvas = new Canvas() );
		canvas.resize( nPixelsWide*pixelScale, nPixelsHigh*pixelScale );
		canvas.show();

		bufferImage = parent.createImage( nPixelsWide*pixelScale, nPixelsHigh*pixelScale );
		bufferGraphics = bufferImage.getGraphics();
		parentGraphics = parent.getGraphics();
		canvasGraphics = canvas.getGraphics();

		parent.add( progressBar = new AMDProgressBar() );
		progressBar.setBarColor( new Color( 192, 52, 4 ) );
		progressBar.show();
		progressBar.hide();

		parent.add( urlField = new TextField() );
		urlField.show();
		urlField.hide();
	}

	public void setBorderWidth( int width ) {
		borderWidth = width;

		canvas.move( borderWidth, borderWidth );

		urlField.reshape( 0, 0,
			parent.preferredSize().width,
			urlField.preferredSize().height );

		progressBar.reshape(
			1, (borderWidth+nPixelsHigh*pixelScale)+2,
			nPixelsWide*pixelScale+borderWidth*2-2, borderWidth-2 );

		progressBar.setFont( urlField.getFont() );
	}

	/**
	 * Z80 hardware interface
	 */
	public int inb( int port ) {
		int res = 0xff;

		if ( (port & 0x0001) == 0 ) {
			if ( (port & 0x8000) == 0 ) { res &= _B_SPC; }
			if ( (port & 0x4000) == 0 ) { res &= _H_ENT; }
			if ( (port & 0x2000) == 0 ) { res &= _Y_P;   }
			if ( (port & 0x1000) == 0 ) { res &= _6_0;   }
			if ( (port & 0x0800) == 0 ) { res &= _1_5;   }
			if ( (port & 0x0400) == 0 ) { res &= _Q_T;   }
			if ( (port & 0x0200) == 0 ) { res &= _A_G;   }
			if ( (port & 0x0100) == 0 ) { res &= _CAPS_V;}
		}

		return(res);
	}
	public void outb( int port, int outByte, int tstates ) {
		if ( (port & 0x0001) == 0 ) {
			newBorder = (outByte & 0x07);
		}
	}

	/** Byte access */
	public void pokeb( int addr, int newByte ) {
		if ( addr >= (22528+768) ) {
			mem[ addr ] = newByte;
			return;
		}

		if ( addr < 16384 ) {
			return;
		}

		if ( mem[ addr ] != newByte ) {
			plot( addr, newByte );
			mem[ addr ] = newByte;
		}	
	}

	// Word access
	public void pokew( int addr, int word ) {
		int _mem[] = mem;

		if ( addr >= (22528+768) ) {
			_mem[ addr ] = word & 0xff;
			if ( ++addr != 65536 ) {
				_mem[ addr ] = word >> 8;
			}
			return;
		}

		if ( addr < 16384 ) {
			return;
		}

		int        newByte0 = word & 0xff;
		if ( _mem[ addr ] != newByte0 ) {
			plot( addr, newByte0 );
			_mem[ addr ] = newByte0;
		}

		int        newByte1 = word >> 8;
		if ( ++addr != (22528+768) ) { 
			if ( _mem[ addr ] != newByte1 ) {
				plot( addr, newByte1 );
				_mem[ addr ] = newByte1;
			}
		}
		else {
			_mem[ addr ] = newByte1;
		}
	}

	/** Since execute runs as a tight loop, some Java VM implementations
	 *  don't allow any other threads to get a look in. This give the
	 *  GUI time to update. If anyone has a better solution please 
	 *  email us at mailto:spectrum@odie.demon.co.uk
	 */
	public  int     sleepHack = 0;
	public  int     refreshRate = 1;  // refresh every 'n' interrupts

	private int     interruptCounter = 0;
	private boolean resetAtNextInterrupt = false;
	private boolean pauseAtNextInterrupt = false;
	private boolean refreshNextInterrupt = true;
	private boolean loadFromURLFieldNextInterrupt = false;

	public  Thread  pausedThread = null;
	public  long    timeOfLastInterrupt = 0;
	private long    timeOfLastSample = 0;


	private void loadFromURLField() {
		try {
			pauseOrResume();

			urlField.hide();
			URL	url = new URL( urlField.getText() );
			URLConnection snap = url.openConnection();

			InputStream input = snap.getInputStream();
			loadSnapshot( url.toString(), input, snap.getContentLength() );
			input.close();
		}
		catch ( Exception e ) {
			showMessage( e.toString() );
		}
	}

	public final int interrupt() {
		if ( pauseAtNextInterrupt ) {
			urlField.show();

			pausedThread = Thread.currentThread();
			while ( pauseAtNextInterrupt ) {
				showMessage( "Adam Davidson & Andrew Pollard" );
				if ( refreshNextInterrupt ) {
					refreshNextInterrupt = false;
					oldBorder = -1;
					paintBuffer();
				}
				if ( loadFromURLFieldNextInterrupt ) {
					loadFromURLFieldNextInterrupt = false;
					loadFromURLField();
				}
				else {
					try { Thread.sleep( 500 ); } catch ( Exception ignored ) {}
				}
			}
			pausedThread = null;

			urlField.hide();
		}

		if ( refreshNextInterrupt ) {
			refreshNextInterrupt = false;
			oldBorder = -1;
			paintBuffer();
		}

		if ( resetAtNextInterrupt ) {
			resetAtNextInterrupt = false;
			reset();
		}

		interruptCounter++;

		// Characters flash every 1/2 a second
		if ( (interruptCounter % 25) == 0 ) {
			refreshFlashChars();
		}

		// Update speed indicator every 2 seconds of 'Spectrum time'
		if ( (interruptCounter % 100) == 0 ) {
			refreshSpeed();
		}

		// Refresh every interrupt by default
		if ( (interruptCounter % refreshRate) == 0 ) {
			screenPaint();
		}

		timeOfLastInterrupt = System.currentTimeMillis();

		// Trying to slow to 100%, browsers resolution on the system
		// time is not accurate enough to check every interrurpt. So
		// we check every 4 interrupts.
		if ( (interruptCounter % 4) == 0 ) {
			long durOfLastInterrupt = timeOfLastInterrupt - timeOfLastSample;
			timeOfLastSample = timeOfLastInterrupt;
			if ( !runAtFullSpeed && (durOfLastInterrupt < 40) ) {
				try { Thread.sleep( 50 - durOfLastInterrupt ); }
				catch ( Exception ignored ) {}
			}
		}

		// This was put in to handle Netscape 2 which was prone to
		// locking up if one thread never gave up its timeslice.
		if ( sleepHack > 0 ) {
			try { Thread.sleep( sleepHack ); }
			catch ( Exception ignored ) {}
		}
		
		return super.interrupt();
	}

	public void pauseOrResume() {
		// Pause
		if ( pausedThread == null ) {
			pauseAtNextInterrupt = true;
		}
		// Resume
		else {
			pauseAtNextInterrupt = false;

		}
	}

	public void repaint() {
		refreshNextInterrupt = true;
	}

	public void reset() {
		super.reset();

		outb( 254, 0xff, 0 ); // White border on startup
	}

	/**
	 * Screen stuff
	 */
	public              int borderWidth = 20;   // absolute, not relative to pixelScale
	public static final int pixelScale  = 2;    // scales pixels in main screen, not border

	public static final int nPixelsWide = 256;
	public static final int nPixelsHigh = 192;
	public static final int nCharsWide  = 32;
	public static final int nCharsHigh  = 24;

	private static final int sat = 238;
	private static final Color brightColors[] = {
		new Color(   0,   0,   0 ),  new Color(   0,   0, sat ),
		new Color( sat,   0,   0 ),  new Color( sat,   0, sat ),
		new Color(   0, sat,   0 ),  new Color(   0, sat, sat ),
		new Color( sat, sat,   0 ),  new Color( sat, sat, sat ),
		Color.black,                 Color.blue,
		Color.red,                   Color.magenta,
		Color.green,                 Color.cyan, 
		Color.yellow,                Color.white
	};
	private static final int firstAttr = (nPixelsHigh*nCharsWide);
	private static final int lastAttr  = firstAttr + (nCharsHigh*nCharsWide);

	/** first screen line in linked list to be redrawn */
	private int first = -1;
	/** first attribute in linked list to be redrawn */
	private int FIRST = -1;
	private final int last[] = new int[ (nPixelsHigh+nCharsHigh)*nCharsWide ];
	private final int next[] = new int[ (nPixelsHigh+nCharsHigh)*nCharsWide ];
	
	public int newBorder = 7;  // White border on startup
	public int oldBorder = -1; // -1 mean update screen

	public long oldTime = 0;
	public int oldSpeed = -1; // -1 mean update progressBar
	public int newSpeed = 0;
	public boolean showStats = true;
	public String statsMessage = null;
	private static final String cancelMessage = new String( "Click here at any time to cancel sleep" );

	private boolean flashInvert = false;


	public final void refreshSpeed() {
		long newTime = timeOfLastInterrupt;

		if ( oldTime != 0 ) {
			newSpeed = (int) (200000.0 / (newTime - oldTime));
		}

		oldTime = newTime;
		if ( (statsMessage != null) && (sleepHack > 0) && (statsMessage != cancelMessage) ) {
			showMessage( cancelMessage );
		}
		else {
			showMessage( null );
		}
	}

	private final void refreshFlashChars() {
		flashInvert = !flashInvert;

		for ( int i = firstAttr; i < lastAttr; i++ ) {
			int attr = mem[i+16384];

			if ( (attr & 0x80) != 0 ) {
				last[i] = (~attr) & 0xff;

				// Only add to update list if not already marked 
				if ( next[i] == -1 ) {
					next[i] = FIRST;
					FIRST = i;
				}
			}
		}
	}

	public final void refreshWholeScreen() {
		showMessage( "Drawing Off-Screen Buffer" );

		for ( int i = 0; i < firstAttr; i++ ) {
			next[ i ] = i-1;
			last[ i ] = (~mem[ i+16384 ]) & 0xff;
		}

		for ( int i = firstAttr; i < lastAttr; i++ ) {
			next[ i ] = -1;
			last[ i ] = mem[ i+16384 ];
		}

		first = firstAttr - 1;
		FIRST = -1;

		oldBorder = -1;
		oldSpeed  = -1;
	}

	private final void plot( int addr, int newByte ) {
		int     offset = addr - 16384;

		if ( next[ offset ] == -1 ) {
			if ( offset < firstAttr ) {
				next[ offset ] = first;
				first = offset;
			}
			else {
				next[ offset ] = FIRST;
				FIRST = offset;
			}
		}
	}

	public final void borderPaint() {
		if ( oldBorder == newBorder ) {
			return;
		}
		oldBorder = newBorder;

		if ( borderWidth == 0 ) {
			return;
		}

		parentGraphics.setColor( brightColors[ newBorder ] );
		parentGraphics.fillRect( 0, 0,
			(nPixelsWide*pixelScale) + borderWidth*2,
			(nPixelsHigh*pixelScale) + borderWidth*2 );
	}

	private static final String fullSpeed = "Full Speed: ";
	private static final String slowSpeed = "Slow Speed: ";
 	public boolean	runAtFullSpeed = true;

	private final void toggleSpeed() {
		runAtFullSpeed = !runAtFullSpeed;
		showMessage( statsMessage );
	}

	public void showMessage( String m ) {
		statsMessage = m;
		oldSpeed = -1; // Force update of progressBar
		statsPaint();
	}

	public final void statsPaint() {
		if ( oldSpeed == newSpeed ) {
			return;
		}
		oldSpeed = newSpeed;

		if ( (!showStats) || (borderWidth < 10) ) {
			return;
		}

		String stats = statsMessage;
		if ( stats == null ) {
			String	speedString = runAtFullSpeed ? fullSpeed : slowSpeed;

			if ( newSpeed > 0 ) {
				stats = speedString + String.valueOf( newSpeed ) + "%";
			}
			else {
				stats = "Speed: calculating";
			}
			if ( sleepHack > 0 ) {
				stats = stats + ", Sleep: " + String.valueOf( sleepHack );
			}
		}
		progressBar.setText( stats );
		progressBar.show();
	}

	public static synchronized Image getImage( Component comp, int attr, int pattern ) {
		try {
			return tryGetImage( comp, attr, pattern );
		}
		catch ( OutOfMemoryError e ) {
			imageMap   = null;
			patternMap = null;

			System.gc();

			patternMap = new Hashtable();
			imageMap   = new Image[ 1<<11 ];

			return tryGetImage( comp, attr, pattern );
		}
	}

	public static Hashtable patternMap = new Hashtable();
	public static Image imageMap[] = new Image[ 1<<11 ]; // 7 bits for attr, 4 bits for pattern

	private static Image tryGetImage( Component comp, int attr, int pattern ) {
		int bright = ((attr>>3) & 0x08);
		int ink = ((attr   ) & 0x07) | bright;
		int pap = ((attr>>3) & 0x07) | bright;
		int hashValue = 0;

		for ( int i = 0; i < 4; i++ ) {
			int col = ((pattern & (1<<i)) == 0) ? pap : ink;
			hashValue |= (col << (i<<2));
		}

		Integer	imageKey = new Integer( hashValue );
		Image image = (Image) patternMap.get( imageKey );
		if ( image == null ) {
			Color	colors[] = brightColors;
			
			image = comp.createImage( 4*pixelScale, 1*pixelScale );
			Graphics g = image.getGraphics();

			for ( int i = 0; i < 4; i++ ) {
				int col = ((pattern & (1<<i)) == 0) ? pap : ink;
				g.setColor( colors[ col ] );
				g.fillRect( (3-i)*pixelScale, 0*pixelScale, 1*pixelScale, 1*pixelScale );
			}

			patternMap.put( imageKey, image );
		}

		return image;
	}

	public final void screenPaint() {
		int addr   = FIRST;

		// Update attribute affected pixels
		while ( addr >= 0 ) {
			int        oldAttr = last[ addr ];
			int        newAttr = mem[ addr + 16384 ];
			last[ addr ] = newAttr;

			boolean    inkChange    = ((oldAttr & 0x47) != (newAttr & 0x47));
			boolean    papChange    = ((oldAttr & 0x78) != (newAttr & 0x78));
			boolean    flashChange  = ((oldAttr & 0x80) != (newAttr & 0x80));

			if ( inkChange || papChange || flashChange ) {
				boolean    allChange = ((inkChange && papChange) || flashChange);
				int        scrAddr   = ((addr & 0x300) << 3) | (addr & 0xff);

				for ( int i = 8; i != 0; i-- ) {

					if ( allChange ) {
						last[ scrAddr ] = ((~mem[ scrAddr+16384 ]) & 0xff);
					}
					else {
						int	oldPixels = last[ scrAddr ];
						int	newPixels = mem[ scrAddr+16384 ];
						int	changes = oldPixels ^ newPixels;

						if ( inkChange ) {
							changes |=  newPixels;
						}
						else {
							changes |= ((~newPixels) & 0xff);
						}
						if ( changes == 0 ) {
							scrAddr += 256;
							continue;
						}
						last[ scrAddr ] = changes ^ newPixels;
					}

					if ( next[ scrAddr ] == -1 ) {
						next[ scrAddr ] = first;
						first = scrAddr;
					}

					scrAddr += 256;
				}
			}

			int        newAddr = next[ addr ];
			next[ addr ] = -1;
			addr = newAddr;
		}
		FIRST = -1;

		// Only update screen if necessary
		if ( first < 0 ) {
			return;
		}

		// Update affected pixels
                addr     = first;
		while ( addr >= 0 ) {
			int oldPixels = last[ addr ];
			int newPixels = mem[ addr+16384 ];
			int changes   = oldPixels ^ newPixels;
			last[ addr ] = newPixels;

			int x = ((addr&0x1f) << 3);
			int y = (((int)(addr&0x00e0))>>2) + 
				(((int)(addr&0x0700))>>8) +
				(((int)(addr&0x1800))>>5);
			int X = (x*pixelScale);
			int Y = (y*pixelScale);

			int attr = mem[ 22528 + (addr&0x1f) + ((y>>3)*nCharsWide) ];

			// Swap colors around if doing flash
			if ( flashInvert && ((attr & 0x80) != 0) ) {
				newPixels = (~newPixels & 0xff);
			}

			// Redraw left nibble if necessary
			if ( (changes & 0xf0) != 0 ) {
				int newPixels1 = (newPixels&0xf0)>>4;
				int imageMapEntry1 = (((attr & 0x7f)<<4) | newPixels1);
				Image image1 = imageMap[ imageMapEntry1 ];
				if ( image1 == null ) {
					image1 = getImage( parent, attr, newPixels1 );
					imageMap[ imageMapEntry1 ] = image1;
				}
				bufferGraphics.drawImage( image1, X, Y, null );
			}

			// Redraw right nibble if necessary
			if ( (changes & 0x0f) != 0 ) {
				int newPixels2 = (newPixels&0x0f);
				int imageMapEntry2 = (((attr & 0x7f)<<4) | newPixels2);
				Image image2 = imageMap[ imageMapEntry2 ];
				if ( image2 == null ) {
					image2 = getImage( parent, attr, newPixels2 );
					imageMap[ imageMapEntry2 ] = image2;
				}
				bufferGraphics.drawImage( image2, X+4*pixelScale, Y, null );
			}

			int        newAddr = next[ addr ];
			next[ addr ] = -1;
			addr = newAddr;
		}
		first = -1;

		paintBuffer();
	}

	public void paintBuffer() {
		canvasGraphics.drawImage( bufferImage, 0, 0, null );
 		borderPaint();

	}

	/** Process events from UI */
	public boolean handleEvent( Event e ) {
		if ( e.target == progressBar ) {
			if ( e.id == Event.MOUSE_DOWN ) {
				if ( sleepHack > 0 ) {
					sleepHack = 0;
					showMessage( "Sleep Cancelled" );
				}
				else {
					toggleSpeed();
				}
				canvas.requestFocus();
				return true;
			}
			return false;
		}

		if ( e.target == urlField ) {
			if ( e.id == Event.ACTION_EVENT ) {
				loadFromURLFieldNextInterrupt = true;
				return true;
			}
			return false;
		}

		switch ( e.id ) {
		case Event.MOUSE_DOWN:
			canvas.requestFocus();
			return true;
		case Event.KEY_ACTION:
		case Event.KEY_PRESS:
			return doKey( true, e.key, e.modifiers );
		case Event.KEY_ACTION_RELEASE:
		case Event.KEY_RELEASE:
			return doKey( false, e.key, e.modifiers );
		case Event.GOT_FOCUS:
		case Event.LOST_FOCUS:
			resetKeyboard();
			return true;
		}

		return false;
	}

	/** Handle Keyboard */
	private static final int b4 = 0x10;
	private static final int b3 = 0x08;
	private static final int b2 = 0x04;
	private static final int b1 = 0x02;
	private static final int b0 = 0x01;

	private int _B_SPC  = 0xff;
	private int _H_ENT  = 0xff;
	private int _Y_P    = 0xff;
	private int _6_0    = 0xff;
	private int _1_5    = 0xff;
	private int _Q_T    = 0xff;
	private int _A_G    = 0xff;
	private int _CAPS_V = 0xff;

	public void resetKeyboard() {
		_B_SPC  = 0xff;
		_H_ENT  = 0xff;
		_Y_P    = 0xff;
		_6_0    = 0xff;
		_1_5    = 0xff;
		_Q_T    = 0xff;
		_A_G    = 0xff;
		_CAPS_V = 0xff;
	}

	private final void K1( boolean down ) {
		if ( down ) _1_5 &= ~b0; else _1_5 |= b0;
	}
	private final void K2( boolean down ) {
		if ( down ) _1_5 &= ~b1; else _1_5 |= b1;
	}
	private final void K3( boolean down ) {
		if ( down ) _1_5 &= ~b2; else _1_5 |= b2;
	}
	private final void K4( boolean down ) {
		if ( down ) _1_5 &= ~b3; else _1_5 |= b3;
	}
	private final void K5( boolean down ) {
		if ( down ) _1_5 &= ~b4; else _1_5 |= b4;
	}

	private final void K6( boolean down ) {
		if ( down ) _6_0 &= ~b4; else _6_0 |= b4;
	}
	private final void K7( boolean down ) {
		if ( down ) _6_0 &= ~b3; else _6_0 |= b3;
	}
	private final void K8( boolean down ) {
		if ( down ) _6_0 &= ~b2; else _6_0 |= b2;
	}
	private final void K9( boolean down ) {
		if ( down ) _6_0 &= ~b1; else _6_0 |= b1;
	}
	private final void K0( boolean down ) {
		if ( down ) _6_0 &= ~b0; else _6_0 |= b0;
	}


	private final void KQ( boolean down ) {
		if ( down ) _Q_T &= ~b0; else _Q_T |= b0;
	}
	private final void KW( boolean down ) {
		if ( down ) _Q_T &= ~b1; else _Q_T |= b1;
	}
	private final void KE( boolean down ) {
		if ( down ) _Q_T &= ~b2; else _Q_T |= b2;
	}
	private final void KR( boolean down ) {
		if ( down ) _Q_T &= ~b3; else _Q_T |= b3;
	}
	private final void KT( boolean down ) {
		if ( down ) _Q_T &= ~b4; else _Q_T |= b4;
	}

	private final void KY( boolean down ) {
		if ( down ) _Y_P &= ~b4; else _Y_P |= b4;
	}
	private final void KU( boolean down ) {
		if ( down ) _Y_P &= ~b3; else _Y_P |= b3;
	}
	private final void KI( boolean down ) {
		if ( down ) _Y_P &= ~b2; else _Y_P |= b2;
	}
	private final void KO( boolean down ) {
		if ( down ) _Y_P &= ~b1; else _Y_P |= b1;
	}
	private final void KP( boolean down ) {
		if ( down ) _Y_P &= ~b0; else _Y_P |= b0;
	}


	private final void KA( boolean down ) {
		if ( down ) _A_G &= ~b0; else _A_G |= b0;
	}
	private final void KS( boolean down ) {
		if ( down ) _A_G &= ~b1; else _A_G |= b1;
	}
	private final void KD( boolean down ) {
		if ( down ) _A_G &= ~b2; else _A_G |= b2;
	}
	private final void KF( boolean down ) {
		if ( down ) _A_G &= ~b3; else _A_G |= b3;
	}
	private final void KG( boolean down ) {
		if ( down ) _A_G &= ~b4; else _A_G |= b4;
	}

	private final void KH( boolean down ) {
		if ( down ) _H_ENT &= ~b4; else _H_ENT |= b4;
	}
	private final void KJ( boolean down ) {
		if ( down ) _H_ENT &= ~b3; else _H_ENT |= b3;
	}
	private final void KK( boolean down ) {
		if ( down ) _H_ENT &= ~b2; else _H_ENT |= b2;
	}
	private final void KL( boolean down ) {
		if ( down ) _H_ENT &= ~b1; else _H_ENT |= b1;
	}
	private final void KENT( boolean down ) {
		if ( down ) _H_ENT &= ~b0; else _H_ENT |= b0;
	}


	private final void KCAPS( boolean down ) {
		if ( down ) _CAPS_V &= ~b0; else _CAPS_V |= b0;
	}
	private final void KZ( boolean down ) {
		if ( down ) _CAPS_V &= ~b1; else _CAPS_V |= b1;
	}
	private final void KX( boolean down ) {
		if ( down ) _CAPS_V &= ~b2; else _CAPS_V |= b2;
	}
	private final void KC( boolean down ) {
		if ( down ) _CAPS_V &= ~b3; else _CAPS_V |= b3;
	}
	private final void KV( boolean down ) {
		if ( down ) _CAPS_V &= ~b4; else _CAPS_V |= b4;
	}

	private final void KB( boolean down ) {
		if ( down ) _B_SPC &= ~b4; else _B_SPC |= b4;
	}
	private final void KN( boolean down ) {
		if ( down ) _B_SPC &= ~b3; else _B_SPC |= b3;
	}
	private final void KM( boolean down ) {
		if ( down ) _B_SPC &= ~b2; else _B_SPC |= b2;
	}
	private final void KSYMB( boolean down ) {
		if ( down ) _B_SPC &= ~b1; else _B_SPC |= b1;
	}
	private final void KSPC( boolean down ) {
		if ( down ) _B_SPC &= ~b0; else _B_SPC |= b0;
	}

	public final boolean doKey( boolean down, int ascii, int mods ) {
		boolean    CAPS = ((mods & Event.CTRL_MASK) != 0);
		boolean    SYMB = ((mods & Event.META_MASK) != 0);
		boolean   SHIFT = ((mods & Event.SHIFT_MASK) != 0);

		// Change control versions of keys to lower case
		if ( (ascii >= 1) && (ascii <= 0x27) && SYMB ) {
			ascii += ('a'-1);
		}

		switch ( ascii ) {
		case 'a':    KA( down );    break;
		case 'b':    KB( down );    break;
		case 'c':    KC( down );    break;
		case 'd':    KD( down );    break;
		case 'e':    KE( down );    break;
		case 'f':    KF( down );    break;
		case 'g':    KG( down );    break;
		case 'h':    KH( down );    break;
		case 'i':    KI( down );    break;
		case 'j':    KJ( down );    break;
		case 'k':    KK( down );    break;
		case 'l':    KL( down );    break;
		case 'm':    KM( down );    break;
		case 'n':    KN( down );    break;
		case 'o':    KO( down );    break;
		case 'p':    KP( down );    break;
		case 'q':    KQ( down );    break;
		case 'r':    KR( down );    break;
		case 's':    KS( down );    break;
		case 't':    KT( down );    break;
		case 'u':    KU( down );    break;
		case 'v':    KV( down );    break;
		case 'w':    KW( down );    break;
		case 'x':    KX( down );    break;
		case 'y':    KY( down );    break;
		case 'z':    KZ( down );    break;
		case '0':    K0( down );    break;
		case '1':    K1( down );    break;
		case '2':    K2( down );    break;
		case '3':    K3( down );    break;
		case '4':    K4( down );    break;
		case '5':    K5( down );    break;
		case '6':    K6( down );    break;
		case '7':    K7( down );    break;
		case '8':    K8( down );    break;
		case '9':    K9( down );    break;
		case ' ':    CAPS = SHIFT;  KSPC( down );  break;

		case 'A':    CAPS = true;   KA( down );    break;
		case 'B':    CAPS = true;   KB( down );    break;
		case 'C':    CAPS = true;   KC( down );    break;
		case 'D':    CAPS = true;   KD( down );    break;
		case 'E':    CAPS = true;   KE( down );    break;
		case 'F':    CAPS = true;   KF( down );    break;
		case 'G':    CAPS = true;   KG( down );    break;
		case 'H':    CAPS = true;   KH( down );    break;
		case 'I':    CAPS = true;   KI( down );    break;
		case 'J':    CAPS = true;   KJ( down );    break;
		case 'K':    CAPS = true;   KK( down );    break;
		case 'L':    CAPS = true;   KL( down );    break;
		case 'M':    CAPS = true;   KM( down );    break;
		case 'N':    CAPS = true;   KN( down );    break;
		case 'O':    CAPS = true;   KO( down );    break;
		case 'P':    CAPS = true;   KP( down );    break;
		case 'Q':    CAPS = true;   KQ( down );    break;
		case 'R':    CAPS = true;   KR( down );    break;
		case 'S':    CAPS = true;   KS( down );    break;
		case 'T':    CAPS = true;   KT( down );    break;
		case 'U':    CAPS = true;   KU( down );    break;
		case 'V':    CAPS = true;   KV( down );    break;
		case 'W':    CAPS = true;   KW( down );    break;
		case 'X':    CAPS = true;   KX( down );    break;
		case 'Y':    CAPS = true;   KY( down );    break;
		case 'Z':    CAPS = true;   KZ( down );    break;

		case '!':    SYMB = true;   K1( down );   break;
		case '@':    SYMB = true;   K2( down );   break;
		case '#':    SYMB = true;   K3( down );   break;
		case '$':    SYMB = true;   K4( down );   break;
		case '%':    SYMB = true;   K5( down );   break;
		case '&':    SYMB = true;   K6( down );   break;
		case '\'':   SYMB = true;   K7( down );   break;
		case '(':    SYMB = true;   K8( down );   break;
		case ')':    SYMB = true;   K9( down );   break;
		case '_':    SYMB = true;   K0( down );   break;

		case '<':    SYMB = true;   KR( down );   break;
		case '>':    SYMB = true;   KT( down );   break;
		case ';':    SYMB = true;   KO( down );   break;
		case '"':    SYMB = true;   KP( down );   break;
		case '^':    SYMB = true;   KH( down );   break;
		case '-':    SYMB = true;   KJ( down );   break;
		case '+':    SYMB = true;   KK( down );   break;
		case '=':    SYMB = true;   KL( down );   break;
		case ':':    SYMB = true;   KZ( down );   break;
		case '£':    SYMB = true;   KX( down );   break;
		case '?':    SYMB = true;   KC( down );   break;
		case '/':    SYMB = true;   KV( down );   break;
		case '*':    SYMB = true;   KB( down );   break;
		case ',':    SYMB = true;   KN( down );   break;
		case '.':    SYMB = true;   KM( down );   break;

		case '[':    SYMB = true;   KY( down );   break;
		case ']':    SYMB = true;   KU( down );   break;
		case '~':    SYMB = true;   KA( down );   break;
		case '|':    SYMB = true;   KS( down );   break;
		case '\\':   SYMB = true;   KD( down );   break;
		case '{':    SYMB = true;   KF( down );   break;
		case '}':    SYMB = true;   KF( down );   break;

		case '\n':
		case '\r':   CAPS = SHIFT; KENT( down );    break;
		case '\t':   CAPS = true; SYMB = true; break;

		case '\b':
		case 127:    CAPS = true; K0( down );    break;

		case Event.F1: CAPS = true; K1( down ); break;
		case Event.F2: CAPS = true; K2( down ); break;
		case Event.F3: CAPS = true; K3( down ); break;
		case Event.F4: CAPS = true; K4( down ); break;
		case Event.F5: CAPS = true; K5( down ); break;
		case Event.F6: CAPS = true; K6( down ); break;
		case Event.F7: CAPS = true; K7( down ); break;
		case Event.F8: CAPS = true; K8( down ); break;
		case Event.F9: CAPS = true; K9( down ); break;
		case Event.F10: CAPS = true; K0( down ); break;
		case Event.F11: CAPS = true; break;
 		case Event.F12: SYMB = true; break;

		case Event.LEFT:    CAPS = SHIFT; K5( down );    break;
		case Event.DOWN:    CAPS = SHIFT; K6( down );    break;
		case Event.UP:      CAPS = SHIFT; K7( down );    break;
		case Event.RIGHT:   CAPS = SHIFT; K8( down );    break;
					
		case Event.END: {
				if ( down ) {
					resetAtNextInterrupt = true;
				}
				break;
			}
		case '\033': // ESC
		case Event.HOME: {
				if ( down ) {
					pauseOrResume();
				}
				break;
			}

		default:
			return false;
		}

		KSYMB( SYMB & down );
		KCAPS( CAPS & down );

		return true;
	}


	public void loadSnapshot( String name, InputStream is, int snapshotLength ) throws Exception {
		// Linux  JDK doesn't always know the size of files
		if ( snapshotLength < 0 ) {
			ByteArrayOutputStream os = new ByteArrayOutputStream();
			is = new BufferedInputStream( is, 4096 );

			int byteOrMinus1;
			int i;

			for ( i = 0; (byteOrMinus1 = is.read()) != -1; i++ ) {
				os.write( (byte) byteOrMinus1 );
			}

			is = new ByteArrayInputStream( os.toByteArray() ); 
			snapshotLength = i;
		}

		// Crude check but it'll work (SNA is a fixed size)
		if ( (snapshotLength == 49179) ) {
			loadSNA( name, is );
		}
		else {
			loadZ80( name, is, snapshotLength );
		}

		refreshWholeScreen();
		resetKeyboard();
	}
	
	public void loadROM( String name, InputStream is ) throws Exception {
		startProgress( "Loading " + name, 16384 );

		readBytes( is, mem, 0, 16384 );
	}

   	public void loadSNA( String name, InputStream is ) throws Exception {
		startProgress( "Loading " + name, 27+49152 );

		int        header[] = new int[27];

		readBytes( is, header, 0,        27 );
		readBytes( is, mem,    16384, 49152 );
    
		I( header[0] );

		HL( header[1] | (header[2]<<8) );
		DE( header[3] | (header[4]<<8) );
		BC( header[5] | (header[6]<<8) );
		AF( header[7] | (header[8]<<8) );

		exx();
		ex_af_af();

		HL( header[9]  | (header[10]<<8) );
		DE( header[11] | (header[12]<<8) );
		BC( header[13] | (header[14]<<8) );

		IY( header[15] | (header[16]<<8) );
		IX( header[17] | (header[18]<<8) );

		if ( (header[19] & 0x04)!= 0 ) {
			IFF2( true );
		}
		else {
			IFF2( false );
		}

		R( header[20] );

		AF( header[21] | (header[22]<<8) );
		SP( header[23] | (header[24]<<8) );

		switch( header[25] ) {
		case 0:
			IM( IM0 );
			break;
		case 1:
			IM( IM1 );
			break;
		default:
			IM( IM2 );
			break;
		}

		outb( 254, header[26], 0 ); // border
     
		/* Emulate RETN to start */
		IFF1( IFF2() );
		REFRESH( 2 );
		poppc();

		if ( urlField != null ) {
			urlField.setText( name );
		}
	}

	public void loadZ80( String name, InputStream is, int bytesLeft ) throws Exception {
		startProgress( "Loading " + name, bytesLeft );

		int        header[] = new int[30];
		boolean    compressed = false;

		bytesLeft -= readBytes( is, header, 0, 30 );

		A( header[0] );
		F( header[1] );
     
		C( header[2] );
		B( header[3] );
		L( header[4] );
		H( header[5] );

		PC( header[6] | (header[7]<<8) );
		SP( header[8] | (header[9]<<8) );

		I( header[10] );
		R( header[11] );

		int tbyte = header[12];
		if ( tbyte == 255 ) {
			tbyte = 1;
		}

		outb( 254, ((tbyte >> 1) & 0x07), 0 ); // border

		if ( (tbyte & 0x01) != 0 ) {
			R( R() | 0x80 );
		}
		compressed = ((tbyte & 0x20) != 0);
     
		E( header[13] );
		D( header[14] );

		ex_af_af();
		exx();

		C( header[15] );
		B( header[16] );
		E( header[17] );
		D( header[18] );
		L( header[19] );
		H( header[20] );

		A( header[21] );
		F( header[22] );

		ex_af_af();
		exx();

		IY( header[23] | (header[24]<<8) );
		IX( header[25] | (header[26]<<8) );

		IFF1( header[27] != 0 );
		IFF2( header[28] != 0 );

		switch ( header[29] & 0x03 ) {
		case 0:
			IM( IM0 );
			break;
		case 1:
			IM( IM1 );
			break;
		default:
			IM( IM2 );
			break;
		}

		if ( PC() == 0 ) {
			loadZ80_extended( is, bytesLeft );

			if ( urlField != null ) {
				urlField.setText( name );
			}
			return;
		}

		/* Old format Z80 snapshot */
    
		if ( compressed ) {
			int data[] = new int[ bytesLeft ];
			int addr   = 16384;

			int size = readBytes( is, data, 0, bytesLeft );
			int i    = 0;

			while ( (addr < 65536) && (i < size) ) {
				tbyte = data[i++];
				if ( tbyte != 0xed ) {
					pokeb( addr, tbyte );
					addr++;
				}
				else {
					tbyte = data[i++];
					if ( tbyte != 0xed ) {
						pokeb( addr, 0xed );
						i--;
						addr++;
					}
					else {
						int        count;
						count = data[i++];
						tbyte = data[i++];
						while ( (count--) != 0 ) {
							pokeb( addr, tbyte );
							addr++;
						}
					}
				}
			}
		}
		else {
			readBytes( is, mem, 16384, 49152 );
		}

		if ( urlField != null ) {
			urlField.setText( name );
		}
	}

	private void loadZ80_extended( InputStream is, int bytesLeft ) throws Exception {
		int header[] = new int[2];
		bytesLeft -= readBytes( is, header, 0, header.length );

		int type = header[0] | (header[1] << 8);

		switch( type ) {
		case 23: /* V2.01 */
			loadZ80_v201( is, bytesLeft );
			break;
		case 54: /* V3.00 */
			loadZ80_v300( is, bytesLeft );
			break;
		case 58: /* V3.01 */
			loadZ80_v301( is, bytesLeft );
			break;
		default:
			throw new Exception( "Z80 (extended): unsupported type " + type );
		}
	}

	private void loadZ80_v201( InputStream is, int bytesLeft ) throws Exception {
		int header[] = new int[23];
		bytesLeft -= readBytes( is, header, 0, header.length );

		PC( header[0] | (header[1]<<8) );

		/* 0 - 48K
		 * 1 - 48K + IF1
		 * 2 - SamRam
		 * 3 - 128K
		 * 4 - 128K + IF1
		 */
		int type = header[2];
	
		if ( type > 1 ) {
			throw new Exception( "Z80 (v201): unsupported type " + type );
		}
		
		int data[] = new int[ bytesLeft ];
		readBytes( is, data, 0, bytesLeft );

		for ( int offset = 0, j = 0; j < 3; j++ ) {
			offset = loadZ80_page( data, offset );
		}
	}

	private void loadZ80_v300( InputStream is, int bytesLeft ) throws Exception {
		int        header[] = new int[54];
		bytesLeft -= readBytes( is, header, 0, header.length );

		PC( header[0] | (header[1]<<8) );

		/* 0 - 48K
		 * 1 - 48K + IF1
		 * 2 - 48K + MGT
		 * 3 - SamRam
		 * 4 - 128K
		 * 5 - 128K + IF1
		 * 6 - 128K + MGT
		 */
		int type = header[2];
	
		if ( type > 6 ) {
			throw new Exception( "Z80 (v300): unsupported type " + type );
		}
		
		int data[] = new int[ bytesLeft ];
		readBytes( is, data, 0, bytesLeft );

		for ( int offset = 0, j = 0; j < 3; j++ ) {
			offset = loadZ80_page( data, offset );
		}
	}

	private void loadZ80_v301( InputStream is, int bytesLeft ) throws Exception {
		int        header[] = new int[58];
		bytesLeft -= readBytes( is, header, 0, header.length );

		PC( header[0] | (header[1]<<8) );

		/* 0 - 48K
		 * 1 - 48K + IF1
		 * 2 - 48K + MGT
		 * 3 - SamRam
		 * 4 - 128K
		 * 5 - 128K + IF1
		 * 6 - 128K + MGT
		 * 7 - +3
		 */
		int type = header[2];
	
		if ( type > 7 ) {
			throw new Exception( "Z80 (v301): unsupported type " + type );
		}
		
		int data[] = new int[ bytesLeft ];
		readBytes( is, data, 0, bytesLeft );

		for ( int offset = 0, j = 0; j < 3; j++ ) {
			offset = loadZ80_page( data, offset );
		}
	}

	private int loadZ80_page( int data[], int i ) throws Exception {
		int blocklen;
		int page;

		blocklen  = data[i++];
		blocklen |= (data[i++]) << 8;
		page = data[i++];

		int addr;
		switch(page) {
		case 4:
			addr = 32768;
			break;
		case 5:
			addr = 49152;
			break;
		case 8:
			addr = 16384;
			break;
		default:
			throw new Exception( "Z80 (page): out of range " + page );
		}

		int        k = 0;
		while (k < blocklen) {
			int        tbyte = data[i++]; k++;
			if ( tbyte != 0xed ) {
				pokeb(addr, ~tbyte);
				pokeb(addr, tbyte);
				addr++;
			}
			else {
				tbyte = data[i++]; k++;
				if ( tbyte != 0xed ) {
					pokeb(addr, 0);
					pokeb(addr, 0xed);
					addr++;
					i--; k--;
				}
				else {
					int        count;
					count = data[i++]; k++;
					tbyte = data[i++]; k++;
					while ( count-- > 0 ) {
						pokeb(addr, ~tbyte);
						pokeb(addr, tbyte);
						addr++;
					}
				}
			}
		}

		if ((addr & 16383) != 0) {
			throw new Exception( "Z80 (page): overrun" );
		}
		
		return i;
	}


	public int bytesReadSoFar = 0;	
	public int bytesToReadTotal = 0;
	
	private void startProgress( String text, int nBytes ) {
		progressBar.setText( text );

		bytesReadSoFar = 0;
		bytesToReadTotal = nBytes;
		updateProgress( 0 );

		if ( showStats ) {
			progressBar.show();
			Thread.yield();
		}
	}

	private void stopProgress() {
		bytesReadSoFar = 0;
		bytesToReadTotal = 0;
		progressBar.setPercent( 0.0 );

		if ( showStats ) {
			progressBar.show();
			Thread.yield();
		}
	}

	private void updateProgress( int bytesRead ) {
		bytesReadSoFar += bytesRead;
		if ( bytesReadSoFar >= bytesToReadTotal ) {
			stopProgress();
			return;
		}
		progressBar.setPercent( (double)bytesReadSoFar / (double)bytesToReadTotal );
		Thread.yield();
	}


	private int readBytes( InputStream is, int a[], int off, int n ) throws Exception {
		try {
			BufferedInputStream bis = new BufferedInputStream( is, n );

			byte buff[] = new byte[ n ];
			int toRead = n;
			while ( toRead > 0 ) {
				int	nRead = bis.read( buff, n-toRead, toRead );
				toRead -= nRead;
				updateProgress( nRead );
			}

			for ( int i = 0; i < n; i++ ) {
				a[ i+off ] = (buff[i]+256)&0xff;
			}

			return n;
		}
		catch ( Exception e ) {
			System.err.println( e );
			e.printStackTrace();
			stopProgress();
			throw e;
		}
	}
}

