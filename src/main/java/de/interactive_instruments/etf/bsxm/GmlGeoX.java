/**
 * Copyright 2010-2016 interactive instruments GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.interactive_instruments.etf.bsxm;

import java.io.File;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import nl.vrom.roo.validator.core.ValidatorContext;
import nl.vrom.roo.validator.core.ValidatorMessage;
import nl.vrom.roo.validator.core.dom4j.handlers.GeometryElementHandler;

import com.github.davidmoten.rtree.geometry.Geometries;
import com.google.common.base.Joiner;
import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.util.GeometryExtracter;
import com.vividsolutions.jts.operation.union.CascadedPolygonUnion;

import org.basex.api.dom.BXElem;
import org.basex.api.dom.BXNode;
import org.basex.core.Context;
import org.basex.data.Data;
import org.basex.query.QueryException;
import org.basex.query.QueryModule;
import org.basex.query.QueryProcessor;
import org.basex.query.value.Value;
import org.basex.query.value.node.ANode;
import org.basex.query.value.node.DBNode;
import org.basex.query.value.seq.Empty;
import org.basex.util.InputInfo;
import org.deegree.cs.persistence.CRSManager;
import org.deegree.geometry.Geometry;
import org.dom4j.io.SAXReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * This module supports the validation of geometries as well as computing the
 * spatial relationship between geometries.
 * <p>
 * NOTE 1: the validation and spatial relationship methods only support specific
 * sets of geometry types - please see the documentation of the respective
 * methods for details on which geometry types are supported.
 *
 * @author Johannes Echterhoff (echterhoff <at> interactive-instruments
 *         <dot> de)
 *
 */
public class GmlGeoX extends QueryModule {

	public static final String NS = "de.interactive_instruments.etf.bsxm.GmlGeoX";
	public static final String PREFIX = "ggeo";

	public enum SpatialRelOp {
		CONTAINS, CROSSES, EQUALS, INTERSECTS, ISDISJOINT, ISWITHIN, OVERLAPS, TOUCHES
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(GmlGeoX.class);

	private static final Pattern INTERSECTIONPATTERN = Pattern.compile("[0-2\\*TF]{9}");

	protected final GmlGeoXUtils geoutils = new GmlGeoXUtils(this);

	private final Set<String> gmlGeometries = new TreeSet<String>();

	private static final boolean debug = LOGGER.isDebugEnabled();

	private GeometryManager mgr = null;

	private int count = 0;
	private int count2 = 0;

	public GmlGeoX() throws QueryException {

		logMemUsage("GmlGeoX#init");

		// default geometry types for which validation is performed
		registerGmlGeometry("Point");
		registerGmlGeometry("Polygon");
		registerGmlGeometry("Surface");
		registerGmlGeometry("Curve");
		registerGmlGeometry("LinearRing");

		registerGmlGeometry("MultiPoint");
		registerGmlGeometry("MultiPolygon");
		registerGmlGeometry("MultiGeometry");
		registerGmlGeometry("MultiSurface");
		registerGmlGeometry("MultiCurve");

		registerGmlGeometry("Ring");
		registerGmlGeometry("LineString");
	}

	/**
	 * Loads SRS configuration files from the given directory, to be used when
	 * looking up SRS names for creating geometry objects.
	 * 
	 * @param configurationDirectoryPathName
	 *            Path to a directory that contains SRS configuration files
	 * @throws QueryException
	 *             in case that the SRS configuration directory does not exist,
	 *             is not a directory, cannot be read, or an exception occurred
	 *             while loading the configuration files
	 */
	@Requires(Permission.NONE)
	public void configureSpatialReferenceSystems(String configurationDirectoryPathName) throws QueryException {

		try {

			File configurationDirectory = new File(configurationDirectoryPathName);

			if (!configurationDirectory.exists() || !configurationDirectory.isDirectory()
					|| !configurationDirectory.canRead()) {

				throw new IllegalArgumentException(
						"Given path name does not identify a directory that exists and that can be read.");

			} else {

				CRSManager crsMgr = new CRSManager();
				crsMgr.init(configurationDirectory);
			}

		} catch (Exception e) {
			throw new QueryException(e);
		}
	}

	/**
	 * Calls the {@link #validate(Object, String)} method, with
	 * <code>null</code> as the bitmask, resulting in a validation with all
	 * tests enabled.
	 * <p>
	 * See the documentation of the {@link #validate(Object, String)} method for
	 * a description of the supported geometry types.
	 *
	 * @param o
	 * @return
	 * @throws QueryException
	 */
	@Requires(Permission.NONE)
	public String validate(Object o) throws QueryException {
		return this.validate(o, null);
	}

	/**
	 * Validates the given (GML geometry) node.
	 * <p>
	 * By default validation is only performed for the following GML geometry
	 * elements: Point, Polygon, Surface, Curve, LinearRing, MultiPolygon,
	 * MultiGeometry, MultiSurface, MultiCurve, Ring, and LineString. The set of
	 * GML elements to validate can be modified via the following methods:
	 * {@link #registerGmlGeometry(String)},
	 * {@link #unregisterGmlGeometry(String)}, and
	 * {@link #unregisterAllGmlGeometries()}. These methods are also available
	 * for XQueries.
	 * <p>
	 * The validation tasks to perform can be specified via the given mask. The
	 * mask is a simple string, where the character '1' at the position of a
	 * specific test (assuming a 1-based index) specifies that the test shall be
	 * performed. If the mask does not contain a character at the position of a
	 * specific test (because the mask is empty or the length is smaller than
	 * the position), then the test will be executed.
	 * <p>
	 * The following tests are available:
	 * <p>
	 * <table>
	 * <tr>
	 * <th>Position</th>
	 * <th>Test Name</th>
	 * <th>Description</th>
	 * </tr>
	 * <tr>
	 * <td>1</td>
	 * <td>General Validation</td>
	 * <td>This test validates the given geometry using the validation
	 * functionality of both deegree and JTS.</td>
	 * </tr>
	 * <tr>
	 * <td>2</td>
	 * <td>Polygon Patch Connectivity</td>
	 * <td>Checks that multiple polygon patches within a single surface are
	 * connected.</td>
	 * </tr>
	 * <tr>
	 * <td>3</td>
	 * <td>Repetition of Position in CurveSegments</td>
	 * <td>Checks that consecutive positions within a CurveSegment are not
	 * equal.</td>
	 * </tr>
	 * </table>
	 * <p>
	 * Examples:
	 * <ul>
	 * <li>The mask '010' indicates that only the 'Polygon Patch Connectivity'
	 * test shall be performed.</li>
	 * <li>The mask '1' indicates that all tests shall be performed (because the
	 * first one is set to true/'1' and nothing is said for the other tests).
	 * </li>
	 * <li>The mask '0' indicates that all except the first test shall be
	 * performed.
	 * </ul>
	 *
	 * @param o
	 *            the GML geometry to validate
	 * @return a mask with the test results, encoded as characters - one at each
	 *         position (1-based index) of the available tests. 'V' indicates
	 *         that the test passed, i.e. that the geometry is valid according
	 *         to that test. 'F' indicates that the test failed. 'S' indicates
	 *         that the test was skipped. Example: the string 'SVF' shows that
	 *         the first test was skipped, while the second test passed and the
	 *         third failed.
	 * @throws QueryException
	 */
	public String validate(Object o, String testMask) throws QueryException {
		ValidationReport vr = this.executeValidate(o, testMask);
		return vr.getValidationResult();
	}

	public Element validateAndReport(Object o) throws QueryException {
		return validateAndReport(o, null);
	}

	/**
	 * @see #executeValidate(Object, String)
	 * @param o
	 * @param testMask
	 * @return a DOM element like the following:
	 *
	 *         <pre>
	 *         {@code
	 *         <ggeo:ValidationResult xmlns:ggeo="de.interactive_instruments.etf.bsxm.GmlGeoX">
	 *           <ggeo:isValid>false</ggeo:isValid>
	 *           <ggeo:result>VFV</ggeo:result>
	 *           <ggeo:message type="NOTICE">Detected GML standard version: GML3.2.</ggeo:message>
	 *           <ggeo:message type="ERROR">Invalid surface (gml:id: s14). The patches of the surface are not connected.</ggeo:message>
	 *         </ggeo:ValidationResult>}
	 *         </pre>
	 *
	 *         Where:
	 *         <ul>
	 *         <li>ggeo:isValid - contains the boolean value indicating if the
	 *         object passed all tests (defined by the testMask).</li>
	 *         <li>ggeo:result - contains a string that is a mask with the test
	 *         results, encoded as characters - one at each position (1-based
	 *         index) of the available tests. 'V' indicates that the test
	 *         passed, i.e. that the geometry is valid according to that test.
	 *         'F' indicates that the test failed. 'S' indicates that the test
	 *         was skipped. Example: the string 'SVF' shows that the first test
	 *         was skipped, while the second test passed and the third failed
	 *         </li>
	 *         <li>ggeo:message (one for each message produced during
	 *         validation) contains:
	 *         <ul>
	 *         <li>an XML attribute 'type' that indicates the severity level of
	 *         the message ('FATAL', 'ERROR', 'WARNING', or 'NOTICE')</li>
	 *         <li>the actual validation message as text content</li>
	 *         </ul>
	 *         </ul>
	 * @throws QueryException
	 */
	public Element validateAndReport(Object o, String testMask) throws QueryException {

		ValidationReport vr = this.executeValidate(o, testMask);

		DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
		docFactory.setNamespaceAware(true);

		DocumentBuilder docBuilder;

		try {
			docBuilder = docFactory.newDocumentBuilder();
		} catch (ParserConfigurationException e) {
			throw new QueryException(e);
		}

		// root elements
		Document doc = docBuilder.newDocument();

		Element root = doc.createElementNS(NS, PREFIX + ":ValidationResult");
		doc.appendChild(root);

		Element isValid = doc.createElementNS(NS, PREFIX + ":isValid");
		isValid.setTextContent(vr.isValid() ? "true" : "false");

		root.appendChild(isValid);

		Element result = doc.createElementNS(NS, PREFIX + ":result");
		result.setTextContent(vr.getValidationResult());

		root.appendChild(result);

		for (ValidatorMessage vm : vr.getValidatorMessages()) {

			Element msg = doc.createElementNS(NS, PREFIX + ":message");
			root.appendChild(msg);

			msg.setAttribute("type", vm.getType().toString());
			msg.setTextContent(vm.getMessage());
		}

		return root;
	}

	/**
	 * Validates the given (GML geometry) node.
	 * <p>
	 * By default validation is only performed for the following GML geometry
	 * elements: Point, Polygon, Surface, Curve, LinearRing, MultiPolygon,
	 * MultiGeometry, MultiSurface, MultiCurve, Ring, and LineString. The set of
	 * GML elements to validate can be modified via the following methods:
	 * {@link #registerGmlGeometry(String)},
	 * {@link #unregisterGmlGeometry(String)}, and
	 * {@link #unregisterAllGmlGeometries()}. These methods are also available
	 * for XQueries.
	 * <p>
	 * The validation tasks to perform can be specified via the given mask. The
	 * mask is a simple string, where the character '1' at the position of a
	 * specific test (assuming a 1-based index) specifies that the test shall be
	 * performed. If the mask does not contain a character at the position of a
	 * specific test (because the mask is empty or the length is smaller than
	 * the position), then the test will be executed.
	 * <p>
	 * The following tests are available:
	 * <p>
	 * <table>
	 * <tr>
	 * <th>Position</th>
	 * <th>Test Name</th>
	 * <th>Description</th>
	 * </tr>
	 * <tr>
	 * <td>1</td>
	 * <td>General Validation</td>
	 * <td>This test validates the given geometry using the validation
	 * functionality of both deegree and JTS.</td>
	 * </tr>
	 * <tr>
	 * <td>2</td>
	 * <td>Polygon Patch Connectivity</td>
	 * <td>Checks that multiple polygon patches within a single surface are
	 * connected.</td>
	 * </tr>
	 * <tr>
	 * <td>3</td>
	 * <td>Repetition of Position in CurveSegments</td>
	 * <td>Checks that consecutive positions within a CurveSegment are not
	 * equal.</td>
	 * </tr>
	 * </table>
	 * <p>
	 * Examples:
	 * <ul>
	 * <li>The mask '010' indicates that only the 'Polygon Patch Connectivity'
	 * test shall be performed.</li>
	 * <li>The mask '1' indicates that all tests shall be performed (because the
	 * first one is set to true/'1' and nothing is said for the other tests).
	 * </li>
	 * <li>The mask '0' indicates that all except the first test shall be
	 * performed.
	 * </ul>
	 *
	 * @param o
	 *            the GML geometry to validate
	 * @return a validation report, with the validation result and validation
	 *         message (providing further details about any errors). The
	 *         validation result is encoded as a sequence of characters - one at
	 *         each position (1-based index) of the available tests. 'V'
	 *         indicates that the test passed, i.e. that the geometry is valid
	 *         according to that test. 'F' indicates that the test failed. 'S'
	 *         indicates that the test was skipped. Example: the string 'SVF'
	 *         shows that the first test was skipped, while the second test
	 *         passed and the third failed.
	 * @throws QueryException
	 */
	@Requires(Permission.NONE)
	ValidationReport executeValidate(Object o, String testMask) throws QueryException {

		try {

			// determine which tests to execute
			boolean isTestGeonovum, isTestPolygonPatchConnectivity, isTestRepetitionInCurveSegments;

			if (testMask == null) {

				isTestGeonovum = true;
				isTestPolygonPatchConnectivity = true;
				isTestRepetitionInCurveSegments = true;

			} else {

				isTestGeonovum = (testMask.length() >= 1 && testMask.charAt(0) == '1') ? true : false;
				isTestPolygonPatchConnectivity = (testMask.length() >= 2 && testMask.charAt(1) == '1') ? true : false;
				isTestRepetitionInCurveSegments = (testMask.length() >= 3 && testMask.charAt(2) == '1') ? true : false;
			}

			boolean isValidGeonovum = false;
			boolean polygonPatchesAreConnected = false;
			boolean noRepetitionInCurveSegment = false;

			BXNode elem;
			String srsName;

			if (o instanceof ANode) {

				ANode node = (ANode) o;
				srsName = determineSrsNameAsString(node);

				elem = node.toJava();

			} else if (o instanceof BXNode) {

				ANode node = ((BXNode) o).getNode();
				srsName = determineSrsNameAsString(node);

				elem = (BXNode) o;
			} else {
				// unknown type encountered
				throw new IllegalArgumentException(
						"Object type '" + o.getClass().getName() + "' is not supported for this method.");
			}

			List<ValidatorMessage> validationMessages = new ArrayList<ValidatorMessage>();
			// ================
			// Geonovum validation (deegree and JTS validation)

			if (isTestGeonovum) {

				ValidatorContext ctx = new ValidatorContext();

				GeometryElementHandler handler = new GeometryElementHandler(ctx, null, srsName);
				/*
				 * configure handler with GML geometries specified through this
				 * class
				 */
				handler.unregisterAllGmlGeometries();
				for (String additionalGmlElementName : gmlGeometries) {
					handler.registerGmlGeometry(additionalGmlElementName);
				}

				SAXReader saxReader = new SAXReader();
				saxReader.setDefaultHandler(handler);

				final InputStream stream = geoutils.nodeToInputStream(elem);
				saxReader.read(stream);

				isValidGeonovum = ctx.isSuccessful();

				if (!isValidGeonovum) {
					List<ValidatorMessage> vmsgs = ctx.getMessages();
					validationMessages.addAll(vmsgs);
					for (ValidatorMessage msg : vmsgs) {
						LOGGER.error(msg.toString());
					}
				}
			}

			if (isTestPolygonPatchConnectivity || isTestRepetitionInCurveSegments) {

				ValidatorContext ctx = new ValidatorContext();
				SecondaryGeometryElementValidationHandler handler = new SecondaryGeometryElementValidationHandler(
						isTestPolygonPatchConnectivity, isTestRepetitionInCurveSegments, ctx, srsName, this);

				/*
				 * configure handler with GML geometries specified through this
				 * class
				 */
				handler.unregisterAllGmlGeometries();
				for (String additionalGmlElementName : gmlGeometries) {
					handler.registerGmlGeometry(additionalGmlElementName);
				}

				SAXReader saxReader = new SAXReader();
				saxReader.setDefaultHandler(handler);

				final InputStream stream = geoutils.nodeToInputStream(elem);
				saxReader.read(stream);

				// ================
				// Test: polygon patches of a surface are connected
				if (isTestPolygonPatchConnectivity) {
					polygonPatchesAreConnected = handler.arePolygonPatchesConnected();
				}

				// ================
				// Test: point repetition in curve segment
				if (isTestRepetitionInCurveSegments) {
					noRepetitionInCurveSegment = handler.isNoRepetitionInCurveSegments();
				}

				if (!polygonPatchesAreConnected || !noRepetitionInCurveSegment) {
					List<ValidatorMessage> vmsgs = ctx.getMessages();
					validationMessages.addAll(vmsgs);
					for (ValidatorMessage msg : vmsgs) {
						LOGGER.error(msg.toString());
					}
				}
			}

			// combine results
			StringBuilder sb = new StringBuilder();

			if (!isTestGeonovum) {
				sb.append("S");
			} else if (isValidGeonovum) {
				sb.append("V");
			} else {
				sb.append("F");
			}

			if (!isTestPolygonPatchConnectivity) {
				sb.append("S");
			} else if (polygonPatchesAreConnected) {
				sb.append("V");
			} else {
				sb.append("F");
			}

			if (!isTestRepetitionInCurveSegments) {
				sb.append("S");
			} else if (noRepetitionInCurveSegment) {
				sb.append("V");
			} else {
				sb.append("F");
			}

			return new ValidationReport(sb.toString(), validationMessages);

		} catch (Exception e) {
			throw new QueryException(e);
		}
	}

	/**
	 * Tests if the first geometry contains the second geometry.
	 * <p>
	 * See {{@link GmlGeoXUtils#toJTSGeometry(Geometry)} for a list of supported
	 * and unsupported geometry types.
	 *
	 * @param arg1
	 *            represents the first geometry, encoded as a GML geometry
	 *            element
	 * @param arg2
	 *            represents the second geometry, encoded as a GML geometry
	 *            element
	 * @return <code>true</code> if the first geometry contains the second one,
	 *         else <code>false</code>.
	 * @throws QueryException
	 */
	@Requires(Permission.NONE)
	@Deterministic
	public boolean contains(Object arg1, Object arg2) throws QueryException {

		return performSpatialRelationshipOperation(arg1, arg2, SpatialRelOp.CONTAINS);
	}

	/**
	 * Tests if one geometry contains a list of geometries. Whether a match is
	 * required for all or just one of these is controlled via parameter.
	 * <p>
	 * See {{@link GmlGeoXUtils#toJTSGeometry(Geometry)} for a list of supported
	 * and unsupported geometry types.
	 *
	 * @param arg1
	 *            represents the first geometry, encoded as a GML geometry
	 *            element
	 * @param arg2
	 *            represents a list of geometries, encoded as a GML geometry
	 *            element
	 * @param matchAll
	 *            <code>true</code> if arg1 must fulfill the spatial
	 *            relationship for all geometries in arg2, else
	 *            <code>false</code>
	 * @return <code>true</code> if the conditions are met, else
	 *         <code>false</code>. <code>false</code> will also be returned if
	 *         arg2 is empty.
	 * @throws QueryException
	 */
	@Requires(Permission.NONE)
	@Deterministic
	public boolean contains(Object arg1, Object arg2, boolean matchAll) throws QueryException {

		return performSpatialRelationshipOperation(arg1, arg2, SpatialRelOp.CONTAINS, matchAll);
	}

	/**
	 * Tests if the first geometry crosses the second geometry.
	 * <p>
	 * See {{@link GmlGeoXUtils#toJTSGeometry(Geometry)} for a list of supported
	 * and unsupported geometry types.
	 *
	 * @param arg1
	 *            represents the first geometry, encoded as a GML geometry
	 *            element
	 * @param arg2
	 *            represents the second geometry, encoded as a GML geometry
	 *            element
	 * @return <code>true</code> if the first geometry crosses the second one,
	 *         else <code>false</code>.
	 * @throws QueryException
	 */
	@Requires(Permission.NONE)
	@Deterministic
	public boolean crosses(Object arg1, Object arg2) throws QueryException {

		return performSpatialRelationshipOperation(arg1, arg2, SpatialRelOp.CROSSES);
	}

	/**
	 * Tests if one geometry crosses a list of geometries. Whether a match is
	 * required for all or just one of these is controlled via parameter.
	 * <p>
	 * See {{@link GmlGeoXUtils#toJTSGeometry(Geometry)} for a list of supported
	 * and unsupported geometry types.
	 *
	 * @param arg1
	 *            represents the first geometry, encoded as a GML geometry
	 *            element
	 * @param arg2
	 *            represents a list of geometries, encoded as a GML geometry
	 *            element
	 * @param matchAll
	 *            <code>true</code> if arg1 must fulfill the spatial
	 *            relationship for all geometries in arg2, else
	 *            <code>false</code>
	 * @return <code>true</code> if the conditions are met, else
	 *         <code>false</code>. <code>false</code> will also be returned if
	 *         arg2 is empty.
	 * @throws QueryException
	 */
	@Requires(Permission.NONE)
	@Deterministic
	public boolean crosses(Object arg1, Object arg2, boolean matchAll) throws QueryException {

		return performSpatialRelationshipOperation(arg1, arg2, SpatialRelOp.CROSSES, matchAll);
	}

	/**
	 * Tests if the first geometry equals the second geometry.
	 * <p>
	 * See {{@link GmlGeoXUtils#toJTSGeometry(Geometry)} for a list of supported
	 * and unsupported geometry types.
	 *
	 * @param arg1
	 *            represents the first geometry, encoded as a GML geometry
	 *            element
	 * @param arg2
	 *            represents the second geometry, encoded as a GML geometry
	 *            element
	 * @return <code>true</code> if the first geometry equals the second one,
	 *         else <code>false</code>.
	 * @throws QueryException
	 */
	@Requires(Permission.NONE)
	@Deterministic
	public boolean equals(Object arg1, Object arg2) throws QueryException {

		return performSpatialRelationshipOperation(arg1, arg2, SpatialRelOp.EQUALS);
	}

	/**
	 * Tests if one geometry equals a list of geometries. Whether a match is
	 * required for all or just one of these is controlled via parameter.
	 * <p>
	 * See {{@link GmlGeoXUtils#toJTSGeometry(Geometry)} for a list of supported
	 * and unsupported geometry types.
	 *
	 * @param arg1
	 *            represents the first geometry, encoded as a GML geometry
	 *            element
	 * @param arg2
	 *            represents a list of geometries, encoded as a GML geometry
	 *            element
	 * @param matchAll
	 *            <code>true</code> if arg1 must fulfill the spatial
	 *            relationship for all geometries in arg2, else
	 *            <code>false</code>
	 * @return <code>true</code> if the conditions are met, else
	 *         <code>false</code>. <code>false</code> will also be returned if
	 *         arg2 is empty.
	 * @throws QueryException
	 */
	@Requires(Permission.NONE)
	@Deterministic
	public boolean equals(Object arg1, Object arg2, boolean matchAll) throws QueryException {

		return performSpatialRelationshipOperation(arg1, arg2, SpatialRelOp.EQUALS, matchAll);
	}

	/**
	 * Tests if the first geometry intersects the second geometry.
	 * <p>
	 * See {{@link GmlGeoXUtils#toJTSGeometry(Geometry)} for a list of supported
	 * and unsupported geometry types.
	 *
	 * @param arg1
	 *            represents the first geometry, encoded as a GML geometry
	 *            element
	 * @param arg2
	 *            represents the second geometry, encoded as a GML geometry
	 *            element
	 * @return <code>true</code> if the first geometry intersects the second
	 *         one, else <code>false</code>.
	 * @throws QueryException
	 */
	@Requires(Permission.NONE)
	@Deterministic
	public boolean intersects(Object arg1, Object arg2) throws QueryException {

		return performSpatialRelationshipOperation(arg1, arg2, SpatialRelOp.INTERSECTS);
	}

	/**
	 * Determine the name of the SRS that applies to the given geometry element.
	 * The SRS is either defined by the element itself, in the 'srsName', or by
	 * the nearest ancestor that either has an 'srsName' attribute or a child
	 * element with local name 'boundedBy' (like gml:boundedBy) that itself
	 * contains a child element (like gml:Envelope) that has an 'srsName'
	 * attribute. NOTE: The underlying query is independent of a specific GML
	 * namespace.
	 * 
	 * @param geometryElement
	 * @return the value of the applicable 'srsName' attribute, if found,
	 *         otherwise the empty sequence
	 * @throws QueryException
	 */
	@Requires(Permission.NONE)
	@Deterministic
	public Value determineSrsName(Value geometryElement) throws QueryException {

		String query = "declare variable $geom external; "
				+ "let $elementWithSrsName := $geom/ancestor-or-self::*[@srsName or *[local-name() = 'boundedBy']/*/@srsName][1] "
				+ "return if (empty($elementWithSrsName)) then () "
				+ "else if($elementWithSrsName/@srsName) then $elementWithSrsName/data(@srsName) else "
				+ "$elementWithSrsName/*[local-name() = 'boundedBy']/*/data(@srsName)";

		Context ctx = queryContext.context;

		try (QueryProcessor qp = new QueryProcessor(query, ctx)) {

			// Bind to context:
			qp.bind("geom", geometryElement);
			qp.context(geometryElement);

			Value value = qp.value();

			return value;
		}
	}

	public com.vividsolutions.jts.geom.Geometry parseGeometry(Object arg) throws QueryException {

		try {
			if (arg instanceof ANode) {

				ANode node = (ANode) arg;

				return geoutils.toJTSGeometry(node);

			} else if (arg instanceof BXElem) {

				BXElem tmp = (BXElem) arg;
				ANode node = tmp.getNode();

				return geoutils.toJTSGeometry(node);

			} else if (arg instanceof com.vividsolutions.jts.geom.Geometry) {

				return (com.vividsolutions.jts.geom.Geometry) arg;

			} else {
				throw new IllegalArgumentException("First argument is neither a single node nor a JTS geometry.");
			}
		} catch (Exception e) {
			throw new QueryException(e);
		}
	}

	/**
	 * Internal method to get the SRS name that applies to a geometry element as
	 * a string.
	 * 
	 * @param node
	 *            The geometry element
	 * @return SRS name that applies to the element, can be <code>null</code> in
	 *         case that no such name was found
	 * @throws QueryException
	 *             if querying for the SRS name resulted in an exception
	 */
	String determineSrsNameAsString(ANode node) throws QueryException {

		Value srsNameValue = determineSrsName(node);
		String srsName;
		if (srsNameValue.isEmpty()) {
			srsName = null;
		} else {
			srsName = srsNameValue.toString();
			// remove any occurrence of " and '
			srsName = srsName.replaceAll("\"|'", "");
		}
		return srsName;
	}

	private boolean performSpatialRelationshipOperation(Object arg1, Object arg2, SpatialRelOp op)
			throws QueryException {

		try {

			com.vividsolutions.jts.geom.Geometry geom1, geom2;

			/*
			 * We require that no basex value with multiple items is provided,
			 * because the developer must explicitly state the desired match
			 * semantics for cases in which one or both arguments is a
			 * collection of items.
			 */
			geom1 = geoutils.singleObjectToJTSGeometry(arg1);
			geom2 = geoutils.singleObjectToJTSGeometry(arg2);

			return applySpatialRelationshipOperator(geom1, geom2, op);

		} catch (

		Exception e) {
			throw new QueryException(e);
		}
	}

	private boolean applySpatialRelationshipOperator(com.vividsolutions.jts.geom.Geometry geom1,
			com.vividsolutions.jts.geom.Geometry geom2, SpatialRelOp op) {

		switch (op) {
		case CONTAINS:
			return geom1.contains(geom2);
		case CROSSES:
			return geom1.crosses(geom2);
		case EQUALS:
			return geom1.equals(geom2);
		case INTERSECTS:
			return geom1.intersects(geom2);
		case ISDISJOINT:
			return geom1.disjoint(geom2);
		case ISWITHIN:
			return geom1.within(geom2);
		case OVERLAPS:
			return geom1.overlaps(geom2);
		case TOUCHES:
			return geom1.touches(geom2);
		default:
			throw new IllegalArgumentException("Unknown spatial relationship operator: " + op.toString());
		}
	}

	private boolean performSpatialRelationshipOperation(Object arg1, Object arg2, SpatialRelOp op, boolean matchAll)
			throws QueryException {

		try {

			if (arg1 instanceof Empty || arg2 instanceof Empty) {

				return false;

			} else {

				com.vividsolutions.jts.geom.Geometry geom1, geom2;

				geom1 = geoutils.toJTSGeometry(arg1);
				geom2 = geoutils.toJTSGeometry(arg2);

				List<com.vividsolutions.jts.geom.Geometry> gc1, gc2;

				gc1 = geoutils.toFlattenedJTSGeometryCollection(geom1);
				gc2 = geoutils.toFlattenedJTSGeometryCollection(geom2);

				boolean allMatch = true;

				outer: for (com.vividsolutions.jts.geom.Geometry g1 : gc1) {
					for (com.vividsolutions.jts.geom.Geometry g2 : gc2) {

						if (matchAll) {

							if (applySpatialRelationshipOperator(g1, g2, op)) {
								/*
								 * check the next geometry pair to see if it
								 * also satisfies the spatial relationship
								 */
							} else {
								allMatch = false;
								break outer;
							}

						} else {

							if (applySpatialRelationshipOperator(g1, g2, op)) {
								return true;
							} else {
								/*
								 * check the next geometry pair to see if it
								 * satisfies the spatial relationship
								 */
							}
						}
					}
				}

				if (matchAll) {
					return allMatch;
				} else {
					return false;
				}
			}

		} catch (Exception e) {
			throw new QueryException(e);
		}
	}

	/**
	 * Tests if one geometry intersects a list of geometries. Whether a match is
	 * required for all or just one of these is controlled via parameter.
	 * <p>
	 * See {{@link GmlGeoXUtils#toJTSGeometry(Geometry)} for a list of supported
	 * and unsupported geometry types.
	 *
	 * @param arg1
	 *            represents the first geometry, encoded as a GML geometry
	 *            element
	 * @param arg2
	 *            represents a list of geometries, encoded as a GML geometry
	 *            element
	 * @param matchAll
	 *            <code>true</code> if arg1 must fulfill the spatial
	 *            relationship for all geometries in arg2, else
	 *            <code>false</code>
	 * @return <code>true</code> if the conditions are met, else
	 *         <code>false</code>. <code>false</code> will also be returned if
	 *         arg2 is empty.
	 * @throws QueryException
	 */
	@Requires(Permission.NONE)
	@Deterministic
	public boolean intersects(Object arg1, Object arg2, boolean matchAll) throws QueryException {

		return performSpatialRelationshipOperation(arg1, arg2, SpatialRelOp.INTERSECTS, matchAll);
	}

	/**
	 * Tests if the first geometry is disjoint from the second geometry.
	 * <p>
	 * See {{@link GmlGeoXUtils#toJTSGeometry(Geometry)} for a list of supported
	 * and unsupported geometry types.
	 *
	 * @param arg1
	 *            represents the first geometry, encoded as a GML geometry
	 *            element
	 * @param arg2
	 *            represents the second geometry, encoded as a GML geometry
	 *            element
	 * @return <code>true</code> if the first geometry is disjoint from the
	 *         second one, else <code>false</code>.
	 * @throws QueryException
	 */
	@Requires(Permission.NONE)
	@Deterministic
	public boolean isDisjoint(Object arg1, Object arg2) throws QueryException {

		return performSpatialRelationshipOperation(arg1, arg2, SpatialRelOp.ISDISJOINT);
	}

	/**
	 * Tests if one geometry is disjoint to a list of geometries. Whether a
	 * match is required for all or just one of these is controlled via
	 * parameter.
	 * <p>
	 * See {{@link GmlGeoXUtils#toJTSGeometry(Geometry)} for a list of supported
	 * and unsupported geometry types.
	 *
	 * @param arg1
	 *            represents the first geometry, encoded as a GML geometry
	 *            element
	 * @param arg2
	 *            represents a list of geometries, encoded as a GML geometry
	 *            element
	 * @param matchAll
	 *            <code>true</code> if arg1 must fulfill the spatial
	 *            relationship for all geometries in arg2, else
	 *            <code>false</code>
	 * @return <code>true</code> if the conditions are met, else
	 *         <code>false</code>. <code>false</code> will also be returned if
	 *         arg2 is empty.
	 * @throws QueryException
	 */
	@Requires(Permission.NONE)
	@Deterministic
	public boolean isDisjoint(Object arg1, Object arg2, boolean matchAll) throws QueryException {

		return performSpatialRelationshipOperation(arg1, arg2, SpatialRelOp.ISDISJOINT, matchAll);
	}

	/**
	 * Tests if the first geometry is within the second geometry.
	 * <p>
	 * See {{@link GmlGeoXUtils#toJTSGeometry(Geometry)} for a list of supported
	 * and unsupported geometry types.
	 *
	 * @param arg1
	 *            represents the first geometry, encoded as a GML geometry
	 *            element
	 * @param arg2
	 *            represents the second geometry, encoded as a GML geometry
	 *            element
	 * @return <code>true</code> if the first geometry is within the second one,
	 *         else <code>false</code>.
	 * @throws QueryException
	 */
	@Requires(Permission.NONE)
	@Deterministic
	public boolean isWithin(Object arg1, Object arg2) throws QueryException {

		return performSpatialRelationshipOperation(arg1, arg2, SpatialRelOp.ISWITHIN);
	}

	/**
	 * Tests if one geometry is within a list of geometries. Whether a match is
	 * required for all or just one of these is controlled via parameter.
	 * <p>
	 * See {{@link GmlGeoXUtils#toJTSGeometry(Geometry)} for a list of supported
	 * and unsupported geometry types.
	 *
	 * @param arg1
	 *            represents the first geometry, encoded as a GML geometry
	 *            element
	 * @param arg2
	 *            represents a list of geometries, encoded as a GML geometry
	 *            element
	 * @param matchAll
	 *            <code>true</code> if arg1 must fulfill the spatial
	 *            relationship for all geometries in arg2, else
	 *            <code>false</code>
	 * @return <code>true</code> if the conditions are met, else
	 *         <code>false</code>. <code>false</code> will also be returned if
	 *         arg2 is empty.
	 * @throws QueryException
	 */
	@Requires(Permission.NONE)
	@Deterministic
	public boolean isWithin(Object arg1, Object arg2, boolean matchAll) throws QueryException {

		return performSpatialRelationshipOperation(arg1, arg2, SpatialRelOp.ISWITHIN, matchAll);
	}

	/**
	 * Tests if the first geometry overlaps the second geometry.
	 * <p>
	 * See {{@link GmlGeoXUtils#toJTSGeometry(Geometry)} for a list of supported
	 * and unsupported geometry types.
	 *
	 * @param arg1
	 *            represents the first geometry, encoded as a GML geometry
	 *            element
	 * @param arg2
	 *            represents the second geometry, encoded as a GML geometry
	 *            element
	 * @return <code>true</code> if the first geometry overlaps the second one,
	 *         else <code>false</code>.
	 * @throws QueryException
	 */
	@Requires(Permission.NONE)
	@Deterministic
	public boolean overlaps(Object arg1, Object arg2) throws QueryException {

		return performSpatialRelationshipOperation(arg1, arg2, SpatialRelOp.OVERLAPS);
	}

	/**
	 * Tests if one geometry overlaps a list of geometries. Whether a match is
	 * required for all or just one of these is controlled via parameter.
	 * <p>
	 * See {{@link GmlGeoXUtils#toJTSGeometry(Geometry)} for a list of supported
	 * and unsupported geometry types.
	 *
	 * @param arg1
	 *            represents the first geometry, encoded as a GML geometry
	 *            element
	 * @param arg2
	 *            represents a list of geometries, encoded as a GML geometry
	 *            element
	 * @param matchAll
	 *            <code>true</code> if arg1 must fulfill the spatial
	 *            relationship for all geometries in arg2, else
	 *            <code>false</code>
	 * @return <code>true</code> if the conditions are met, else
	 *         <code>false</code>. <code>false</code> will also be returned if
	 *         arg2 is empty.
	 * @throws QueryException
	 */
	@Requires(Permission.NONE)
	@Deterministic
	public boolean overlaps(Object arg1, Object arg2, boolean matchAll) throws QueryException {

		return performSpatialRelationshipOperation(arg1, arg2, SpatialRelOp.OVERLAPS, matchAll);
	}

	/**
	 * Tests if the first geometry touches the second geometry.
	 * <p>
	 * See {{@link GmlGeoXUtils#toJTSGeometry(Geometry)} for a list of supported
	 * and unsupported geometry types.
	 *
	 * @param arg1
	 *            represents the first geometry, encoded as a GML geometry
	 *            element
	 * @param arg2
	 *            represents the second geometry, encoded as a GML geometry
	 *            element
	 * @return <code>true</code> if the first geometry touches the second one,
	 *         else <code>false</code>.
	 * @throws QueryException
	 */
	@Requires(Permission.NONE)
	@Deterministic
	public boolean touches(Object arg1, Object arg2) throws QueryException {

		return performSpatialRelationshipOperation(arg1, arg2, SpatialRelOp.TOUCHES);
	}

	/**
	 * Tests if one geometry touches a list of geometries. Whether a match is
	 * required for all or just one of these is controlled via parameter.
	 * <p>
	 * See {{@link GmlGeoXUtils#toJTSGeometry(Geometry)} for a list of supported
	 * and unsupported geometry types.
	 *
	 * @param arg1
	 *            represents the first geometry, encoded as a GML geometry
	 *            element
	 * @param arg2
	 *            represents a list of geometries, encoded as a GML geometry
	 *            element
	 * @param matchAll
	 *            <code>true</code> if arg1 must fulfill the spatial
	 *            relationship for all geometries in arg2, else
	 *            <code>false</code>
	 * @return <code>true</code> if the conditions are met, else
	 *         <code>false</code>. <code>false</code> will also be returned if
	 *         arg2 is empty.
	 * @throws QueryException
	 */
	@Requires(Permission.NONE)
	@Deterministic
	public boolean touches(Object arg1, Object arg2, boolean matchAll) throws QueryException {

		return performSpatialRelationshipOperation(arg1, arg2, SpatialRelOp.TOUCHES, matchAll);
	}

	/**
	 * Adds the name of a GML geometry element to the set of elements for which
	 * validation is performed.
	 *
	 * @param nodeName
	 *            name (simple, i.e. without namespace (prefix)) of a GML
	 *            geometry element to validate.
	 */
	@Requires(Permission.NONE)
	public void registerGmlGeometry(String nodeName) {
		gmlGeometries.add(nodeName);
	}

	/**
	 * Removes the name of a GML geometry element from the set of elements for
	 * which validation is performed.
	 *
	 * @param nodeName
	 *            name (simple, i.e. without namespace (prefix)) of a GML
	 *            geometry element to remove from validation.
	 */
	@Requires(Permission.NONE)
	public void unregisterGmlGeometry(String nodeName) {
		gmlGeometries.remove(nodeName);
	}

	/**
	 * Removes all names of GML geometry elements that are currently registered
	 * for validation.
	 */
	@Requires(Permission.NONE)
	public void unregisterAllGmlGeometries() {
		gmlGeometries.clear();
	}

	/**
	 * @return the currently registered GML geometry element names (comma
	 *         separated)
	 */
	@Requires(Permission.NONE)
	public String registeredGmlGeometries() {

		if (gmlGeometries.isEmpty()) {
			return "";
		} else {
			Joiner joiner = Joiner.on(", ").skipNulls();
			return joiner.join(gmlGeometries);
		}
	}

	@Requires(Permission.NONE)
	@Deterministic
	public com.vividsolutions.jts.geom.Geometry union(Object arg1, Object arg2) throws QueryException {

		try {

			List<com.vividsolutions.jts.geom.Geometry> geoms = new ArrayList<com.vividsolutions.jts.geom.Geometry>();

			com.vividsolutions.jts.geom.Geometry geom1 = geoutils.toJTSGeometry(arg1);
			geoms.add(geom1);

			com.vividsolutions.jts.geom.Geometry geom2 = geoutils.toJTSGeometry(arg2);
			geoms.add(geom2);

			com.vividsolutions.jts.geom.GeometryCollection gc = geoutils.toJTSGeometryCollection(geoms, true);

			return gc.union();

		} catch (Exception e) {
			throw new QueryException(e);
		}
	}

	@Requires(Permission.NONE)
	@Deterministic
	public com.vividsolutions.jts.geom.Geometry union(Object arg) throws QueryException {

		try {

			List<com.vividsolutions.jts.geom.Geometry> geoms = new ArrayList<com.vividsolutions.jts.geom.Geometry>();

			com.vividsolutions.jts.geom.Geometry geom = geoutils.toJTSGeometry(arg);
			geoms.add(geom);

			com.vividsolutions.jts.geom.GeometryCollection gc = geoutils.toJTSGeometryCollection(geoms, true);

			return gc.union();

		} catch (Exception e) {
			throw new QueryException(e);
		}
	}

	@Requires(Permission.NONE)
	@Deterministic
	public boolean isEmpty(com.vividsolutions.jts.geom.Geometry geom) {

		if (geom == null || geom.isEmpty()) {
			return true;
		} else {
			return false;
		}
	}

	/**
	 * Checks if a given object is closed. The object can be a single geometry
	 * or a collection of geometries. Only LineStrings and MultiLineStrings are
	 * checked.
	 *
	 * NOTE: Invokes the {@link #isClosed(Object, boolean)} method, with
	 * <code>true</code> for the second parameter.
	 *
	 * @see #isClosed(Object, boolean)
	 * @param o
	 * @return
	 * @throws QueryException
	 */
	@Requires(Permission.NONE)
	@Deterministic
	public boolean isClosed(Object o) throws QueryException {
		return isClosed(o, true);
	}

	/**
	 * Checks if a given object is closed. The object can be a single geometry
	 * or a collection of geometries. Points and MultiPoints are closed by
	 * definition (they do not have a boundary). Polygons and MultiPolygons are
	 * never closed in 2D, and since operations in 3D are not supported, this
	 * method will always return <code>false</code> if a polygon is encountered
	 * - unless the parameter onlyCheckCurveGeometries is set to
	 * <code>true</code>. LinearRings are closed by definition. The remaining
	 * geometry types that will be checked are LineString and MultiLineString.
	 * If a (Multi)LineString is not closed, this method will return
	 * <code>false</code>.
	 *
	 * @param o
	 *            the geometry object(s) to test, can be a JTS geometry object,
	 *            collection, and BaseX nodes (that will be converted to JTS
	 *            geometries)
	 * @param onlyCheckCurveGeometries
	 *            <code>true</code> if only curve geometries (i.e., for JTS:
	 *            LineString, LinearRing, and MultiLineString) shall be tested,
	 *            else <code>false</code> (in this case, the occurrence of
	 *            polygons will result in the return value <code>false</code>).
	 * @return <code>true</code> if the given object - a geometry or collection
	 *         of geometries - is closed, else <code>false</code>
	 * @throws QueryException
	 */
	@Requires(Permission.NONE)
	@Deterministic
	public boolean isClosed(Object o, boolean onlyCheckCurveGeometries) throws QueryException {

		try {

			if (o instanceof Empty) {

				return true;

			} else {

				com.vividsolutions.jts.geom.Geometry geom = geoutils.toJTSGeometry(o);

				List<com.vividsolutions.jts.geom.Geometry> gc = geoutils.toFlattenedJTSGeometryCollection(geom);

				for (com.vividsolutions.jts.geom.Geometry g : gc) {

					if (g instanceof com.vividsolutions.jts.geom.Point
							|| g instanceof com.vividsolutions.jts.geom.MultiPoint) {

						/*
						 * points are closed by definition (they do not have a
						 * boundary)
						 */

					} else if (g instanceof com.vividsolutions.jts.geom.Polygon
							|| g instanceof com.vividsolutions.jts.geom.MultiPolygon) {

						/*
						 * The JTS FAQ contains the following question and
						 * answer:
						 *
						 * Question: Does JTS support 3D operations?
						 *
						 * Answer: JTS does not provide support for true 3D
						 * geometry and operations. However, JTS does allow
						 * Coordinates to carry an elevation or Z value. This
						 * does not provide true 3D support, but does allow
						 * "2.5D" uses which are required in some geospatial
						 * applications.
						 *
						 * -------
						 *
						 * So, JTS does not support true 3D geometry and
						 * operations. Therefore, JTS cannot determine if a
						 * surface is closed. deegree does not seem to support
						 * this, either. In order for a surface to be closed, it
						 * must be a sphere or torus, possibly with holes. A
						 * surface in 2D can never be closed. Since we lack the
						 * ability to compute in 3D we assume that a
						 * (Multi)Polygon is not closed. If we do check
						 * geometries other than curves, then we return false.
						 */
						if (!onlyCheckCurveGeometries) {
							return false;
						}

					} else if (g instanceof com.vividsolutions.jts.geom.MultiLineString) {

						com.vividsolutions.jts.geom.MultiLineString mls = (com.vividsolutions.jts.geom.MultiLineString) g;
						if (!mls.isClosed()) {
							return false;
						}

					} else if (g instanceof com.vividsolutions.jts.geom.LineString) {

						/*
						 * NOTE: LinearRing is a subclass of LineString, and
						 * closed by definition
						 */

						com.vividsolutions.jts.geom.LineString ls = (com.vividsolutions.jts.geom.LineString) g;
						if (!ls.isClosed()) {
							return false;
						}

					} else {
						// should not happen
						throw new Exception("Unexpected geometry type encountered: " + g.getClass().getName());
					}
				}

				// all relevant geometries are closed
				return true;
			}

		} catch (Exception e) {
			throw new QueryException(e);
		}
	}

	/**
	 * Identifies the holes contained in the given geometry (can be a Polygon,
	 * MultiPolygon, or any other JTS geometry) and returns them as a JTS
	 * geometry. If holes were found a union is built, to ensure that the result
	 * is a valid JTS Polygon or JTS MultiPolygon. If no holes were found an
	 * empty JTS GeometryCollection is returned.
	 *
	 * @param geom
	 *            potentially existing holes will be extracted from this
	 *            geometry
	 * @return A geometry with the holes contained in the given geometry. Can be
	 *         empty but not <code>null</code>;
	 */
	@Requires(Permission.NONE)
	@Deterministic
	public com.vividsolutions.jts.geom.Geometry holes(com.vividsolutions.jts.geom.Geometry geom) {

		if (isEmpty(geom)) {

			return geoutils.emptyJTSGeometry();

		} else {

			List<com.vividsolutions.jts.geom.Polygon> extractedPolygons = new ArrayList<com.vividsolutions.jts.geom.Polygon>();

			GeometryExtracter.extract(geom, com.vividsolutions.jts.geom.Polygon.class, extractedPolygons);

			if (extractedPolygons.isEmpty()) {

				return geoutils.emptyJTSGeometry();

			} else {

				// get holes as polygons
				List<com.vividsolutions.jts.geom.Polygon> holes = new ArrayList<com.vividsolutions.jts.geom.Polygon>();

				for (com.vividsolutions.jts.geom.Polygon polygon : extractedPolygons) {

					// check that polygon has holes
					if (polygon.getNumInteriorRing() > 0) {

						// for each hole, convert it to a polygon
						for (int i = 0; i < polygon.getNumInteriorRing(); i++) {
							com.vividsolutions.jts.geom.LineString ls = polygon.getInteriorRingN(i);
							com.vividsolutions.jts.geom.Polygon holeAsPolygon = geoutils.toJTSPolygon(ls);
							holes.add(holeAsPolygon);
						}
					}
				}

				if (holes.isEmpty()) {
					return geoutils.emptyJTSGeometry();
				} else {
					// create union of holes and return it
					return CascadedPolygonUnion.union(holes);
				}
			}
		}
	}

	@Requires(Permission.NONE)
	public boolean isValid(Object o) throws QueryException {

		if (o == null || o instanceof Empty) {

			return false;

		} else if (o instanceof BXElem || o instanceof ANode) {

			String validationResult = validate(o);

			if (validationResult.toLowerCase().indexOf('f') > -1) {
				return false;
			} else {
				return true;
			}

		} else if (o instanceof Value) {

			Value v = (Value) o;

			if (v.size() > 1) {
				throw new IllegalArgumentException("Single value expected where multiple were provided.");
			}

		} else if (o instanceof Object[]) {
			throw new IllegalArgumentException("Single object expected where multiple were provided.");
		}

		// unknown type encountered
		throw new IllegalArgumentException(
				"Object type '" + o.getClass().getName() + "' is not supported for this method.");
	}

	/**
	 * Tests if the first geometry relates to the second geometry as defined by
	 * the given intersection pattern.
	 * <p>
	 * See {{@link GmlGeoXUtils#toJTSGeometry(Geometry)} for a list of supported
	 * and unsupported geometry types.
	 *
	 * @param arg1
	 *            represents the first geometry, encoded as a GML geometry
	 *            element
	 * @param arg2
	 *            represents the second geometry, encoded as a GML geometry
	 *            element
	 * @param intersectionPattern
	 *            the pattern against which to check the intersection matrix for
	 *            the two geometries (IxI,IxB,IxE,BxI,BxB,BxE,ExI,ExB,ExE)
	 * @return <code>true</code> if the DE-9IM intersection matrix for the two
	 *         geometries matches the <code>intersectionPattern</code>, else
	 *         <code>false</code>.
	 * @throws QueryException
	 */
	@Requires(Permission.NONE)
	@Deterministic
	public boolean relate(Object arg1, Object arg2, String intersectionPattern) throws QueryException {

		checkIntersectionPattern(intersectionPattern);

		try {

			com.vividsolutions.jts.geom.Geometry geom1, geom2;

			/*
			 * We require that no basex value with multiple items is provided,
			 * because the developer must explicitly state the desired match
			 * semantics for cases in which one or both arguments is a
			 * collection of items.
			 */
			geom1 = geoutils.singleObjectToJTSGeometry(arg1);
			geom2 = geoutils.singleObjectToJTSGeometry(arg2);

			return geom1.relate(geom2, intersectionPattern);

		} catch (Exception e) {
			throw new QueryException(e);
		}
	}

	/**
	 * Tests if one geometry relates to a list of geometries as defined by the
	 * given intersection pattern. Whether a match is required for all or just
	 * one of these is controlled via parameter.
	 * <p>
	 * See {{@link GmlGeoXUtils#toJTSGeometry(Geometry)} for a list of supported
	 * and unsupported geometry types.
	 *
	 * @param arg1
	 *            represents the first geometry, encoded as a GML geometry
	 *            element
	 * @param arg2
	 *            represents a list of geometries, encoded as a GML geometry
	 * @param intersectionPattern
	 *            the pattern against which to check the intersection matrix for
	 *            the geometries (IxI,IxB,IxE,BxI,BxB,BxE,ExI,ExB,ExE)
	 * @param matchAll
	 *            <code>true</code> if arg1 must fulfill the spatial
	 *            relationship defined by the <code>intersectionPattern</code>
	 *            for all geometries in arg2, else <code>false</code>
	 * @return <code>true</code> if the conditions are met, else
	 *         <code>false</code>. <code>false</code> will also be returned if
	 *         arg2 is empty.
	 * @throws QueryException
	 */
	@Requires(Permission.NONE)
	@Deterministic
	public boolean relate(Object arg1, Object arg2, String intersectionPattern, boolean matchAll)
			throws QueryException {

		checkIntersectionPattern(intersectionPattern);

		try {

			if (arg1 instanceof Empty || arg2 instanceof Empty) {

				return false;

			} else {

				com.vividsolutions.jts.geom.Geometry geom1, geom2;

				geom1 = geoutils.toJTSGeometry(arg1);
				geom2 = geoutils.toJTSGeometry(arg2);

				List<com.vividsolutions.jts.geom.Geometry> gc1, gc2;

				gc1 = geoutils.toFlattenedJTSGeometryCollection(geom1);
				gc2 = geoutils.toFlattenedJTSGeometryCollection(geom2);

				boolean allMatch = true;

				outer: for (com.vividsolutions.jts.geom.Geometry g1 : gc1) {
					for (com.vividsolutions.jts.geom.Geometry g2 : gc2) {

						if (matchAll) {

							if (g1.relate(g2, intersectionPattern)) {
								/*
								 * check the next geometry pair to see if it
								 * also satisfies the spatial relationship
								 */
							} else {
								allMatch = false;
								break outer;
							}

						} else {

							if (g1.relate(g2, intersectionPattern)) {
								return true;
							} else {
								/*
								 * check the next geometry pair to see if it
								 * satisfies the spatial relationship
								 */
							}
						}
					}
				}

				if (matchAll) {
					return allMatch;
				} else {
					return false;
				}
			}

		} catch (Exception e) {
			throw new QueryException(e);
		}
	}

	private void checkIntersectionPattern(String intersectionPattern) throws QueryException {

		if (intersectionPattern == null) {

			throw new QueryException("intersectionPattern is null.");

		} else {

			Matcher m = INTERSECTIONPATTERN.matcher(intersectionPattern.trim());

			if (!m.matches()) {
				throw new QueryException(
						"intersectionPattern does not match the regular expression, which is: [0-2\\\\*TF]{9}");
			}
		}

	}

	/**
	 * Computes the intersection between the first and the second geometry.
	 * <p>
	 * See {{@link GmlGeoXUtils#toJTSGeometry(Geometry)} for a list of supported
	 * and unsupported geometry types.
	 *
	 * @param arg1
	 *            represents the first geometry
	 * @param arg2
	 *            represents the second geometry
	 * @return the point-set common to the two geometries
	 * @throws QueryException
	 */
	@Requires(Permission.NONE)
	@Deterministic
	public com.vividsolutions.jts.geom.Geometry intersection(Object arg1, Object arg2) throws QueryException {

		try {

			if (arg1 instanceof Empty || arg2 instanceof Empty) {

				return geoutils.emptyJTSGeometry();

			} else {

				com.vividsolutions.jts.geom.Geometry geom1, geom2;

				geom1 = geoutils.toJTSGeometry(arg1);
				geom2 = geoutils.toJTSGeometry(arg2);

				return geom1.intersection(geom2);
			}

		} catch (Exception e) {
			throw new QueryException(e);
		}

	}

	/**
	 * Computes the envelope of a geometry.
	 * <p>
	 * See {@link GmlGeoXUtils#toJTSGeometry(Geometry)} for a list of supported
	 * and unsupported geometry types.
	 *
	 * @param arg
	 *            represents the geometry
	 * @return The bounding box, an array { x1, y1, x2, y2 }
	 * @throws QueryException
	 */
	@Requires(Permission.NONE)
	@Deterministic
	public Object[] envelope(Object arg) throws QueryException {

		try {
			Envelope env;

			if (arg instanceof Empty) {
				env = geoutils.emptyJTSGeometry().getEnvelopeInternal();
			} else if (arg instanceof com.vividsolutions.jts.geom.Geometry) {
				env = ((com.vividsolutions.jts.geom.Geometry) arg).getEnvelopeInternal();
			} else {
				env = geoutils.toJTSGeometry(arg).getEnvelopeInternal();
			}
			Object[] res = { env.getMinX(), env.getMinY(), env.getMaxX(), env.getMaxY() };
			return res;

		} catch (Exception e) {
			throw new QueryException(e);
		}

	}

	@Requires(Permission.NONE)
	@Deterministic
	public int pre(Object entry) {
		if (entry instanceof IndexEntry)
			return ((IndexEntry) entry).pre;

		return -1;
	}

	@Requires(Permission.NONE)
	@Deterministic
	public String dbname(Object entry) {
		if (entry instanceof IndexEntry)
			return ((IndexEntry) entry).dbname;

		return null;
	}

	/**
	 * Searches the spatial r-tree index for items in the envelope.
	 *
	 * @param minx
	 *            represents the minimum value on the first coordinate axis; a
	 *            number
	 * @param miny
	 *            represents the minimum value on the second coordinate axis; a
	 *            number
	 * @param maxx
	 *            represents the maximum value on the first coordinate axis; a
	 *            number
	 * @param maxy
	 *            represents the maximum value on the second coordinate axis; a
	 *            number
	 * @return the node set of all items in the envelope
	 * @throws QueryException
	 */
	@Requires(Permission.NONE)
	@Deterministic
	public Object[] search(Object minx, Object miny, Object maxx, Object maxy) throws QueryException {

		try {
			double x1;
			double x2;
			double y1;
			double y2;
			if (minx instanceof Number)
				x1 = ((Number) minx).doubleValue();
			else
				x1 = 0.0;
			if (miny instanceof Number)
				y1 = ((Number) miny).doubleValue();
			else
				y1 = 0.0;
			if (maxx instanceof Number)
				x2 = ((Number) maxx).doubleValue();
			else
				x2 = 0.0;
			if (maxy instanceof Number)
				y2 = ((Number) maxy).doubleValue();
			else
				y2 = 0.0;

			if (mgr == null)
				mgr = new GeometryManager();
			Iterable<IndexEntry> iter = mgr.search(Geometries.rectangle(x1, y1, x2, y2));
			List<DBNode> nodelist = new ArrayList<DBNode>();
			for (IndexEntry entry : iter) {
				Data d = queryContext.resources.database(entry.dbname, new InputInfo("xpath", 0, 0));
				DBNode n = new DBNode(d, entry.pre);
				if (n != null)
					nodelist.add(n);
			}
			if (++count % 5000 == 0) {
				logMemUsage("GmlGeoX#search " + count + ". Box: (" + x1 + ", " + y1 + ") (" + x2 + ", " + y2 + ")"
						+ ". Hits: " + nodelist.size());
			}

			return nodelist.toArray();

		} catch (Exception e) {
			throw new QueryException(e);
		}
	}

	/**
	 * Returns all items in the spatial r-tree index.
	 *
	 * @return the node set of all items in the index
	 * @throws QueryException
	 */
	public Object[] search() throws QueryException {
		try {
			logMemUsage("GmlGeoX#search.start " + count + ".");
			if (mgr == null)
				mgr = new GeometryManager();
			Iterable<IndexEntry> iter = mgr.search();
			List<DBNode> nodelist = new ArrayList<DBNode>();
			for (IndexEntry entry : iter) {
				Data d = queryContext.resources.database(entry.dbname, new InputInfo("xpath", 0, 0));
				DBNode n = new DBNode(d, entry.pre);
				if (n != null)
					nodelist.add(n);
			}
			logMemUsage("GmlGeoX#search " + count + ". Hits: " + nodelist.size());

			return nodelist.toArray();

		} catch (Exception e) {
			throw new QueryException(e);
		}
	}

	/**
	 * Logs memory information if Logger is enabled for the DEBUG level
	 *
	 * @param progress
	 *            status string
	 */

	private void logMemUsage(final String progress) {
		if (debug) {
			final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
			memory.gc();
			LOGGER.debug(progress + ". Memory: " + Math.round(memory.getHeapMemoryUsage().getUsed() / 1048576)
					+ " MB of " + Math.round(memory.getHeapMemoryUsage().getMax() / 1048576) + " MB.");
		}
	}

	/**
	 * Set cache size for geometries
	 *
	 * @param size
	 *            the size of the geometry cache; default is 100000
	 * @throws QueryException
	 */
	@Requires(Permission.NONE)
	public void cacheSize(Object size) throws QueryException {
		if (size instanceof BigInteger) {
			mgr = new GeometryManager(((BigInteger) size).intValue());
		}
	}

	/**
	 * Indexes a list of id nodes (gml:id attribute of features) with their GML
	 * geometries
	 * <p>
	 * See {@link GmlGeoXUtils#toJTSGeometry(Geometry)} for a list of supported
	 * and unsupported geometry types.
	 * <p>
	 * Both lists must have equal length.
	 *
	 * @param pre
	 *            represents the pre value of the indexed item node (typically
	 *            the gml:id of GML feature elements)
	 * @param dbname
	 *            represents the name of the database that contains the indexed
	 *            item node (typically the gml:id of GML feature elements)
	 * @param id
	 *            represents the id string of the item that should be indexed,
	 *            typically the gml:id of GML feature elements; must be String
	 *            instances
	 * @param geom
	 *            represents the GML geometry to index; must be an BXElem
	 *            instance
	 * @throws QueryException
	 */
	@Requires(Permission.NONE)
	public void index(Object pre, Object dbname, Object id, Object geom) throws QueryException {
		if (mgr == null)
			mgr = new GeometryManager();

		if (pre instanceof BigInteger && dbname instanceof String && (id instanceof BXNode || id instanceof String)
				&& (geom instanceof BXElem || geom instanceof com.vividsolutions.jts.geom.Geometry))
			try {
				IndexEntry entry = new IndexEntry((String) dbname, ((BigInteger) pre).intValue());
				String _id = id instanceof String ? (String) id : ((BXNode) id).getNodeValue();
				com.vividsolutions.jts.geom.Geometry _geom = geom instanceof BXElem
						? geoutils.singleObjectToJTSGeometry(geom) : ((com.vividsolutions.jts.geom.Geometry) geom);
				Envelope env = _geom.getEnvelopeInternal();
				if (!env.isNull()) {
					if (env.getHeight() == 0.0 && env.getWidth() == 0.0)
						mgr.index(entry, Geometries.point(env.getMinX(), env.getMinY()));
					else
						mgr.index(entry,
								Geometries.rectangle(env.getMinX(), env.getMinY(), env.getMaxX(), env.getMaxY()));

					// add to geometry cache
					if (_id != null)
						mgr.put(_id, _geom);
				}

				int size = mgr.indexSize();
				if (size % 5000 == 0)
					logMemUsage("GmlGeoX#index progress: " + size);

			} catch (Exception e) {
				throw new QueryException(e);
			}
	}

	/**
	 * Retrieve the geometry of an item as a JTS geometry. First try the cache
	 * and if it is not in the cache construct it from the XML.
	 * <p>
	 * See {@link GmlGeoXUtils#toJTSGeometry(Geometry)} for a list of supported
	 * and unsupported geometry types.
	 *
	 * @param id
	 *            the id for which the geometry should be retrieved, typically a
	 *            gml:id of a GML feature element; must be a String or BXNode
	 *            instance
	 * @param defgeom
	 *            represents the default GML geometry, if the geometry is not
	 *            cached; must be a BXElem instance
	 * @return the geometry of the indexed node, or null if no geometry was
	 *         found
	 * @throws QueryException
	 */
	@Requires(Permission.NONE)
	@Deterministic
	public com.vividsolutions.jts.geom.Geometry getGeometry(Object id, Object defgeom) throws QueryException {
		if (++count2 % 5000 == 0) {
			logMemUsage("GmlGeoX#getGeometry.start " + count2);
		}

		if (mgr == null)
			mgr = new GeometryManager();

		String idx;
		if (id instanceof String) {
			idx = (String) id;
		} else if (id instanceof BXNode) {
			idx = ((BXNode) id).getNodeValue();
		} else
			throw new QueryException(
					"Failure to get geometry. An id uses an incorrect type: " + id.getClass().getCanonicalName());

		com.vividsolutions.jts.geom.Geometry geom = mgr.get(idx);
		if (geom == null) {
			if (!(defgeom instanceof BXElem || defgeom instanceof com.vividsolutions.jts.geom.Geometry)) {
				throw new QueryException(
						"Failure to parse geometry. A geometry was not found or uses an incorrect type: "
								+ defgeom.getClass().getCanonicalName());
			}
			try {
				geom = defgeom instanceof BXElem ? geoutils.singleObjectToJTSGeometry(defgeom)
						: ((com.vividsolutions.jts.geom.Geometry) defgeom);
				if (geom != null)
					mgr.put(idx, geom);
			} catch (Exception e) {
				throw new QueryException(e);
			}
			if (debug) {
				long missCount = mgr.getMissCount();
				if (missCount % 10000 == 0) {
					LOGGER.debug("Cache misses: " + missCount + " of " + mgr.getCount());
				}
			}
		}

		if (geom == null) {
			geom = geoutils.emptyJTSGeometry();
		}

		if (count2 % 5000 == 0) {
			logMemUsage("GmlGeoX#getGeometry.end " + count2);
		}

		return geom;
	}
}
