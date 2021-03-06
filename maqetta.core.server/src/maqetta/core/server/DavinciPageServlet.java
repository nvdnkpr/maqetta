package maqetta.core.server;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.net.URL;
import java.net.URLConnection;
import java.util.Calendar;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.davinci.ajaxLibrary.ILibraryManager;
import org.davinci.server.internal.Activator;
import org.davinci.server.user.IUser;
import org.davinci.server.user.IUserManager;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.maqetta.server.IDavinciServerConstants;
import org.maqetta.server.IServerManager;
import org.maqetta.server.IVResource;
import org.maqetta.server.ServerManager;
import org.maqetta.server.VURL;
import org.osgi.framework.Bundle;

public class DavinciPageServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;
	private static final String LAST_MODIFIED = "Last-Modified"; //$NON-NLS-1$
	private static final String IF_MODIFIED_SINCE = "If-Modified-Since"; //$NON-NLS-1$
	private static final String IF_NONE_MATCH = "If-None-Match"; //$NON-NLS-1$
	private static final String ETAG = "ETag"; //$NON-NLS-1$
	private static final String CACHE_CONTROL = "Cache-Control";

	protected IUserManager userManager;
	protected IServerManager serverManager;
	protected ILibraryManager libraryManager;

	public void initialize() {
		serverManager = ServerManager.getServerManger();
		userManager = serverManager.getUserManager();
		libraryManager = serverManager.getLibraryManager();
	}

	/*
	 * Save file request from user. Saves files to workspace. (non-Javadoc)
	 * 
	 * @see
	 * javax.servlet.http.HttpServlet#doPut(javax.servlet.http.HttpServletRequest
	 * , javax.servlet.http.HttpServletResponse)
	 */
	@Override
	protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		IUser user = ServerManager.getServerManger().getUserManager().getUser(req);
		if(user==null){
			resp.sendError(HttpServletResponse.SC_FORBIDDEN);
			resp.getOutputStream().close();
			return;
		}
		String path = getPathInfo(req);
		boolean isWorkingCopy = (path.indexOf(IDavinciServerConstants.WORKING_COPY_EXTENSION) > -1);
		if ( isWorkingCopy ) {
			path = path.substring(0, path.indexOf(IDavinciServerConstants.WORKING_COPY_EXTENSION));
		}
		IVResource file = user.getResource(path);
		/* user is trying to save over a library path */
		if ( file.isVirtual() ) {
			file = user.createResource(path, file.isDirectory());
			if(file.isDirectory())
				file.mkdir();
			else
			   file.createNewInstance();

		}
		if ( file.exists() ) {
			OutputStream os = file.getOutputStreem();
			transferStreams(req.getInputStream(), os, false);
			if ( !isWorkingCopy ) {
				// flush the working copy
				file.flushWorkingCopy();
			}

		} else {
			resp.sendError(HttpServletResponse.SC_NOT_FOUND);
		}
		resp.getOutputStream().close();
	}

	public String getPathInfo(HttpServletRequest req){
		return req.getPathInfo();
	}
	
	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		if ( serverManager == null ) {
			initialize();
		}
		String previewParam = req.getParameter(IDavinciServerConstants.PREVIEW_PARAM);

		IUser user = (IUser) req.getSession().getAttribute(IDavinciServerConstants.SESSION_USER);
		String pathInfo = getPathInfo(req);
		if ( ServerManager.DEBUG_IO_TO_CONSOLE ) {
			System.out.println("Page Servlet request: " + pathInfo + ", logged in=" + (user != null));
		}
		
		if ( pathInfo == null ) {
			handleReview(req, resp);
			resp.sendRedirect("./maqetta/");
		} else if ( pathInfo != null && (pathInfo.equals("") || pathInfo.equals("/")) && previewParam == null ) {
			if ( !ServerManager.LOCAL_INSTALL ) {
				if ( user == null ) {
					resp.sendRedirect("./welcome");
				} else {
					writeMainPage(req, resp);
				}
			} else {
				/* local install, set user to single user */
				user = this.userManager.getSingleUser();
				req.getSession().setAttribute(IDavinciServerConstants.SESSION_USER, user);
				Cookie k = new Cookie(IDavinciServerConstants.SESSION_USER, user.getUserName() );
		  		k.setPath("/");
		  		resp.addCookie(k);
		  		writeMainPage(req, resp);
			}
		} else if ( pathInfo.equals("/welcome") ) {
			/* write the welcome page (may come from extension point) */
			writeWelcomePage(req, resp);
		} else if ( previewParam != null ) {
			handlePreview(req, resp);
		} else if ( pathInfo.startsWith(IDavinciServerConstants.USER_URL) ) {
			handleWSRequest(req, resp, user);
		} else {
			/* resource not found */
			resp.sendError(HttpServletResponse.SC_NOT_FOUND);
		}
		resp.getOutputStream().close();
	}
	
	/*
	 * If review-related attributes are in the URL, set cookies so that
	 * review gets opened on the client.
	 */
	private void handleReview(HttpServletRequest req, HttpServletResponse resp) {
		String designerName = req.getParameter(IDavinciServerConstants.REVIEW_DESIGNER_ATTR);
		String reviewVersion = req.getParameter(IDavinciServerConstants.REVIEW_VERSION_ATTR);
		if (validateReviewParms(designerName, reviewVersion)) {
			//Fill in designer cookie
			Cookie designerCookie = new Cookie(IDavinciServerConstants.REVIEW_COOKIE_DESIGNER, designerName);
			/* have to set the path to delete it later from the client */
			designerCookie.setPath("/");
			resp.addCookie(designerCookie);

			//Fill in review version cookie
			if ( reviewVersion != null ) {
				Cookie versionCookie = new Cookie(IDavinciServerConstants.REVIEW_COOKIE_VERSION, reviewVersion);
				/* have to set the path to delete it later from the client */
				versionCookie.setPath("/");
				resp.addCookie(versionCookie);
			}
		}
	}
	
	/*
	 * Make sure we have valid parms before putting back out into cookies. Putting 
	 * unsanitized/unvalidated parms straight into a cookie can open up the door for XSS attacks.
	 */
	private boolean validateReviewParms(String designerName, String reviewVersion) {
		boolean returnVal = (designerName != null && reviewVersion != null);
		if (returnVal) {
			boolean validDesigner = ServerManager.getServerManger().getUserManager().isValidUser(designerName);
			if (validDesigner) {
				//We're not going to look up the reviewVersion, but we're at least going to make sure it
				//only contains letters and numbers
				Pattern p = Pattern.compile("[^a-zA-z0-9]");
				Matcher m = p.matcher(reviewVersion);
				returnVal = !m.find();
				if (!returnVal) {
					if ( ServerManager.DEBUG_IO_TO_CONSOLE ) {
						System.out.println("validateReviewParms: Poorly formatted reviewVersion = " + reviewVersion);
					}
				}
			} else {
				if ( ServerManager.DEBUG_IO_TO_CONSOLE ) {
					System.out.println("validateReviewParms: Invalid review designer name = " + designerName);
				}
				returnVal = false;
			}
		}
		return returnVal;
	}

	/*
	 * Find a plugin with the given extension name and calculate winner based on
	 * priority.
	 * 
	 * @param extensionName the extension point name
	 * 
	 * @return Bundle
	 */
	protected URL getPageExtensionPath(String extensionPoint, String extensionName) {

		List extensions = serverManager.getExtensions(extensionPoint, extensionName);
		IConfigurationElement winner = null;
		int highest = -100000;
		for (int i = 0; i < extensions.size(); i++) {
			IConfigurationElement extension = (IConfigurationElement) extensions.get(i);
			int priority = Integer.parseInt(extension.getAttribute(IDavinciServerConstants.EP_ATTR_PAGE_PRIORITY));
			if ( priority > highest ) {
				winner = extension;
				highest = priority;
			}
		}
		String name = winner.getDeclaringExtension().getContributor().getName();
		Bundle bundle = Activator.getActivator().getOtherBundle(name);
		String path = winner.getAttribute(IDavinciServerConstants.EP_ATTR_PAGE_PATH);

		return bundle.getResource(path);
	}

	protected void writeWelcomePage(HttpServletRequest req, HttpServletResponse resp) throws ServletException,
			IOException {
		URL welcomePage = getPageExtensionPath(IDavinciServerConstants.EXTENSION_POINT_WELCOME_PAGE,
				IDavinciServerConstants.EP_TAG_WELCOME_PAGE);
		VURL resourceURL = new VURL(welcomePage);
		this.writePage(req, resp, resourceURL, false);
	}

	protected void writeMainPage(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {

		URL welcomePage = getPageExtensionPath(IDavinciServerConstants.EXTENSION_POINT_MAIN_PAGE,
				IDavinciServerConstants.EP_TAG_MAIN_PAGE);
		VURL resourceURL = new VURL(welcomePage);
		this.writePage(req, resp, resourceURL, false);
	}

	/*
	 * Used for previewing mobile pages.
	 */
	protected void handlePreview(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
		URL previewPage = getPageExtensionPath(IDavinciServerConstants.EXTENSION_POINT_PREVIEW_PAGE,
				IDavinciServerConstants.EP_TAG_PREVIEW_PAGE);
		VURL resourceURL = new VURL(previewPage);
		this.writePage(req, resp, resourceURL, false);
	}

	protected void handleWSRequest(HttpServletRequest req, HttpServletResponse resp, IUser user) throws IOException,
			ServletException {

		// System.out.println("enter ws request");
		String pathInfo =getPathInfo(req);
		// Code further down expects pathInfo==null if user goes to root of daVinci server
		IPath path = new Path(pathInfo);
		if ( path.hasTrailingSeparator() ) {
			path = path.removeTrailingSeparator();
		}

		path = path.removeFirstSegments(1);
		String userName = path.segment(0);
		if ( path.segmentCount() < 4 || !path.segment(1).equals("ws") || !path.segment(2).equals("workspace") ) {
			if ( ServerManager.DEBUG_IO_TO_CONSOLE ) {
				System.out.println("incorrectly formed workspace url");
			}
			resp.sendError(HttpServletResponse.SC_BAD_REQUEST);
			return;
		}
		path = path.removeFirstSegments(3);

		/* unlocking user directory to un-authenticated users */
		if ( user == null ) {
			user = ServerManager.getServerManger().getUserManager().getUser(userName);
		}

		if(user!=null && user.getUserName().compareTo(userName)!=0){
			user =  ServerManager.getServerManger().getUserManager().getUser(userName);
		}
		
		if ( handleLibraryRequest(req, resp, path, user) ) {
			// System.out.println("was library");
			return;
		}

		if ( user == null ) {
			user = ServerManager.getServerManger().getUserManager().getUser(userName);
			if ( user == null ) {
				if ( ServerManager.DEBUG_IO_TO_CONSOLE ) {
					System.out.println("user not found: " + userName);
				}
				resp.sendError(HttpServletResponse.SC_NOT_FOUND);
				return;
			}
		}

		IVResource userFile = user.getResource(path.toString());

		if ( userFile != null && !userFile.exists() ) {
			if ( path.getFileExtension() == null ) {
				userFile = user.getResource(path.addFileExtension("html").toString());
			}
			if ( !userFile.exists() ) {
				if ( ServerManager.DEBUG_IO_TO_CONSOLE ) {
					System.out.println("user file not found: " + path);
				}
				resp.sendError(HttpServletResponse.SC_NOT_FOUND);
				return;
			}
		} else {
			resp.resetBuffer();
			resp.sendError(HttpServletResponse.SC_NOT_FOUND);
		}

	}

	protected boolean handleLibraryRequest(HttpServletRequest req, HttpServletResponse resp, IPath path, IUser user)
			throws ServletException, IOException {
		IVResource libraryURL = user.getResource(path.toString());
		if ( libraryURL != null ) {
			writePage(req, resp, libraryURL, false);
			return true;
		}
		return false;
	}

	protected void writePage(HttpServletRequest req, HttpServletResponse resp, IVResource resourceURL,
			boolean cacheExpires) throws ServletException, IOException {

		if ( resourceURL == null ) {
			if ( ServerManager.DEBUG_IO_TO_CONSOLE ) {
				System.out.println("resource URL not found");
			}
			resp.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}
		String path = resourceURL.getPath();
		boolean noCache = resourceURL.getPath().endsWith(".html");
		URLConnection connection = resourceURL.openConnection();
		long lastModified = connection.getLastModified();
		int contentLength = connection.getContentLength();

		String etag = null;
		if ( lastModified != -1 && contentLength != -1 ) {
			etag = "W/\"" + contentLength + "-" + lastModified + "\"";
		}

		// Check for cache revalidation.
		// We should prefer ETag validation as the guarantees are stronger and
		// all HTTP 1.1 clients should be using it
		String ifNoneMatch = req.getHeader(DavinciPageServlet.IF_NONE_MATCH);
		if ( ifNoneMatch != null && etag != null && ifNoneMatch.indexOf(etag) != -1 && !noCache ) {
			resp.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
			return;
		}

		long ifModifiedSince = req.getDateHeader(DavinciPageServlet.IF_MODIFIED_SINCE);
		// for purposes of comparison we add 999 to ifModifiedSince since the fidelity
		// of the IMS header generally doesn't include milli-seconds
		if ( ifModifiedSince > -1 && lastModified > 0 && lastModified <= (ifModifiedSince + 999) && !noCache ) {
			resp.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
			return;
		}

		// return the full contents regularly
		if ( contentLength != -1 ) {
			resp.setContentLength(contentLength);
		}

		String contentType = req.getSession().getServletContext().getMimeType(path);
		if ( contentType != null ) {
			resp.setContentType(contentType);
		}

		if ( lastModified > 0 ) {
			resp.setDateHeader(DavinciPageServlet.LAST_MODIFIED, lastModified);
		}

		resp.setCharacterEncoding("UTF-8");

		if ( etag != null ) {
			resp.setHeader(DavinciPageServlet.ETAG, etag);
		}

		if ( !cacheExpires && !noCache ) {
			String dateStamp = "Mon, 25 Aug " + (Calendar.getInstance().get(Calendar.YEAR) + 5) + " 01:00:00 GMT";
			resp.setHeader(DavinciPageServlet.CACHE_CONTROL, "Expires:" + dateStamp);
		} else if ( noCache ) {
			resp.setDateHeader("Expires", 0);
			resp.setHeader("Cache-Control", "no-cache"); // HTTP 1.1
			resp.setHeader("Pragma", "no-cache"); // HTTP 1.0
		}

	
		// open the input stream
		InputStream is = null;
		try {
			is = connection.getInputStream();
			// write the resource
			try {
				OutputStream os = resp.getOutputStream();
				int writtenContentLength = writeResource(is, os);
				if ( contentLength == -1 || contentLength != writtenContentLength ) {
					resp.setContentLength(writtenContentLength);
				}
			} catch ( IllegalStateException e ) { 
				// can occur if the response output is already open as a Writer
				Writer writer = resp.getWriter();
				writeResource(is, writer);
				// Since ContentLength is a measure of the number of bytes contained in the body
				// of a message when we use a Writer we lose control of the exact byte count and
				// defer the problem to the Servlet Engine's Writer implementation.
			}
		} catch ( FileNotFoundException e ) {
			// FileNotFoundException may indicate the following scenarios
			// 		- url is a directory
			// 		- url is not accessible
			if ( ServerManager.DEBUG_IO_TO_CONSOLE ) {
				System.out.println("file not found exception: " + e.toString());
			}
			resp.reset();
			resp.sendError(HttpServletResponse.SC_FORBIDDEN);

		} catch ( SecurityException e ) {
			// SecurityException may indicate the following scenarios
			// 		- url is not accessible
			resp.reset();
			resp.sendError(HttpServletResponse.SC_FORBIDDEN);
		} finally {
			if ( is != null ) {
				is.close();
			}
		}
		
	}

	protected void writeInternalPage(HttpServletRequest req, HttpServletResponse resp, Bundle bundle, String path)
			throws ServletException, IOException {
		VURL resourceURL = new VURL(bundle.getResource(path));
		writePage(req, resp, resourceURL, false);
	}

	protected static int writeResource(InputStream is, OutputStream os) throws IOException {
		byte[] buffer = new byte[8192];
		int bytesRead = is.read(buffer);
		int writtenContentLength = 0;
		while ( bytesRead != -1 ) {
			os.write(buffer, 0, bytesRead);
			writtenContentLength += bytesRead;
			bytesRead = is.read(buffer);
		}
		return writtenContentLength;
	}

	protected static void writeResource(InputStream is, Writer writer) throws IOException {
		Reader reader = new InputStreamReader(is);
		try {
			char[] buffer = new char[8192];
			int charsRead = reader.read(buffer);
			while ( charsRead != -1 ) {
				writer.write(buffer, 0, charsRead);
				charsRead = reader.read(buffer);
			}
		} finally {
			if ( reader != null ) {
				reader.close(); // will also close input stream
			}
		}
	}

	protected static final void transferStreams(InputStream source, OutputStream destination, boolean closeInput)
			throws IOException {
		byte[] buffer = new byte[8192];
		try {
			synchronized ( buffer ) {
				while ( true ) {
					int bytesRead = -1;
					bytesRead = source.read(buffer);
					if ( bytesRead == -1 ) {
						break;
					}
					destination.write(buffer, 0, bytesRead);
				}
			}
		} finally {
			if ( closeInput ) {
				source.close();
			} else {
				destination.close();
			}
		}
	}
}
