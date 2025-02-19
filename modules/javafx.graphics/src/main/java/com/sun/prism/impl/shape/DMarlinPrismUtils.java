/*
 * Copyright (c) 2011, 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package com.sun.prism.impl.shape;


import com.sun.javafx.geom.PathIterator;
import com.sun.javafx.geom.Path2D;
import com.sun.javafx.geom.Rectangle;
import com.sun.javafx.geom.Shape;
import com.sun.javafx.geom.transform.BaseTransform;
import com.sun.marlin.MarlinConst;
import com.sun.marlin.DMarlinRenderer;
import com.sun.marlin.DPathConsumer2D;
import com.sun.marlin.DRendererContext;
import com.sun.marlin.DTransformingPathConsumer2D;
import com.sun.prism.BasicStroke;

public final class DMarlinPrismUtils {

    private static final boolean FORCE_NO_AA = false;

    static final float UPPER_BND = Float.MAX_VALUE / 2.0f;
    static final float LOWER_BND = -UPPER_BND;

    /**
     * Private constructor to prevent instantiation.
     */
    private DMarlinPrismUtils() {
    }

    private static boolean nearZero(final double num) {
        return Math.abs(num) < 2.0d * Math.ulp(num);
    }

    private static DPathConsumer2D initPipeline(
            final DRendererContext rdrCtx,
            final BasicStroke stroke,
            final float lineWidth,
            final BaseTransform tx,
            final DPathConsumer2D out)
    {
        // We use strokerat so that in Stroker and Dasher we can work only
        // with the pre-transformation coordinates. This will repeat a lot of
        // computations done in the path iterator, but the alternative is to
        // work with transformed paths and compute untransformed coordinates
        // as needed. This would be faster but I do not think the complexity
        // of working with both untransformed and transformed coordinates in
        // the same code is worth it.
        // However, if a path's width is constant after a transformation,
        // we can skip all this untransforming.

        // As pathTo() will check transformed coordinates for invalid values
        // (NaN / Infinity) to ignore such points, it is necessary to apply the
        // transformation before the path processing.
        BaseTransform strokerTx = null;

        int dashLen = -1;
        boolean recycleDashes = false;
        double scale = 1.0d;
        double width = 0.0f, dashphase = 0.0f;
        double[] dashesD = null;

        if (stroke != null) {
            width = lineWidth;
            final float[] dashes = stroke.getDashArray();
            dashphase = stroke.getDashPhase();

            // Ensure converting dashes to double precision:
            if (dashes != null) {
                recycleDashes = true;
                dashLen = dashes.length;
                dashesD = rdrCtx.dasher.copyDashArray(dashes);
            }

            if ((tx != null) && !tx.isIdentity()) {
                final double a = tx.getMxx();
                final double b = tx.getMxy();
                final double c = tx.getMyx();
                final double d = tx.getMyy();

                // If the transform is a constant multiple of an orthogonal transformation
                // then every length is just multiplied by a constant, so we just
                // need to transform input paths to stroker and tell stroker
                // the scaled width. This condition is satisfied if
                // a*b == -c*d && a*a+c*c == b*b+d*d. In the actual check below, we
                // leave a bit of room for error.
                if (nearZero(a*b + c*d) && nearZero(a*a + c*c - (b*b + d*d))) {
                    scale = Math.sqrt(a*a + c*c);

                    if (dashesD != null) {
                        for (int i = 0; i < dashLen; i++) {
                            dashesD[i] *= scale;
                        }
                        dashphase *= scale;
                    }
                    width *= scale;

                    // by now strokerat == null. Input paths to
                    // stroker (and maybe dasher) will have the full transform tx
                    // applied to them and nothing will happen to the output paths.
                } else {
                    strokerTx = tx;

                    // by now strokerat == tx. Input paths to
                    // stroker (and maybe dasher) will have the full transform tx
                    // applied to them, then they will be normalized, and then
                    // the inverse of *only the non translation part of tx* will
                    // be applied to the normalized paths. This won't cause problems
                    // in stroker, because, suppose tx = T*A, where T is just the
                    // translation part of tx, and A is the rest. T*A has already
                    // been applied to Stroker/Dasher's input. Then Ainv will be
                    // applied. Ainv*T*A is not equal to T, but it is a translation,
                    // which means that none of stroker's assumptions about its
                    // input will be violated. After all this, A will be applied
                    // to stroker's output.
                }
            }
        }

        // Prepare the pipeline:
        DPathConsumer2D pc = out;

        if (MarlinConst.USE_SIMPLIFIER) {
            // Use simplifier after stroker before Renderer
            // to remove collinear segments (notably due to cap square)
            pc = rdrCtx.simplifier.init(pc);
        }

        final DTransformingPathConsumer2D transformerPC2D = rdrCtx.transformerPC2D;

        if (stroke != null) {
            pc = transformerPC2D.deltaTransformConsumer(pc, strokerTx);

            pc = rdrCtx.stroker.init(pc, width, stroke.getEndCap(),
                    stroke.getLineJoin(), stroke.getMiterLimit());

            if (dashesD != null) {
                pc = rdrCtx.dasher.init(pc, dashesD, dashLen, dashphase, recycleDashes);
            }
            pc = transformerPC2D.inverseDeltaTransformConsumer(pc, strokerTx);
        }

        /*
         * Pipeline seems to be:
         * shape.getPathIterator(tx)
         * -> (inverseDeltaTransformConsumer)
         * -> (Dasher)
         * -> Stroker
         * -> (deltaTransformConsumer)
         *
         * -> (CollinearSimplifier) to remove redundant segments
         *
         * -> pc2d = Renderer (bounding box)
         */
        return pc;
    }

    private static DPathConsumer2D initRenderer(
            final DRendererContext rdrCtx,
            final BasicStroke stroke,
            final BaseTransform tx,
            final Rectangle clip,
            final int piRule,
            final DMarlinRenderer renderer)
    {
        final int oprule = ((stroke == null) && (piRule == PathIterator.WIND_EVEN_ODD)) ?
            DMarlinRenderer.WIND_EVEN_ODD : DMarlinRenderer.WIND_NON_ZERO;

        renderer.init(clip.x, clip.y, clip.width, clip.height, oprule);

        float lw = 0.0f;

        if (stroke != null) {
            lw = stroke.getLineWidth();
        }

        return initPipeline(rdrCtx, stroke, lw, tx, renderer);
    }

    public static DMarlinRenderer setupRenderer(
            final DRendererContext rdrCtx,
            final Shape shape,
            final BasicStroke stroke,
            final BaseTransform xform,
            final Rectangle rclip,
            final boolean antialiasedShape)
    {
        // Test if transform is identity:
        final BaseTransform tf = ((xform != null) && !xform.isIdentity()) ? xform : null;

        final DMarlinRenderer r =  (!FORCE_NO_AA && antialiasedShape) ?
                rdrCtx.renderer : rdrCtx.getRendererNoAA();

        if (shape instanceof Path2D) {
            final Path2D p2d = (Path2D)shape;
            final DPathConsumer2D pc2d = initRenderer(rdrCtx, stroke, tf, rclip, p2d.getWindingRule(), r);
            feedConsumer(rdrCtx, p2d, tf, pc2d);
        } else {
            final PathIterator pi = shape.getPathIterator(tf);
            final DPathConsumer2D pc2d = initRenderer(rdrCtx, stroke, tf, rclip, pi.getWindingRule(), r);
            feedConsumer(rdrCtx, pi, pc2d);
        }
        return r;
    }

    public static void strokeTo(
            final DRendererContext rdrCtx,
            final Shape shape,
            final BasicStroke stroke,
            final float lineWidth,
            final DPathConsumer2D out)
    {
        final DPathConsumer2D pc2d = initPipeline(rdrCtx, stroke, lineWidth, null, out);

        if (shape instanceof Path2D) {
            feedConsumer(rdrCtx, (Path2D)shape, null, pc2d);
        } else {
            feedConsumer(rdrCtx, shape.getPathIterator(null), pc2d);
        }
    }

    private static void feedConsumer(final DRendererContext rdrCtx, final PathIterator pi,
                                     final DPathConsumer2D pc2d)
    {
        // mark context as DIRTY:
        rdrCtx.dirty = true;

        final float[] coords = rdrCtx.float6;

        // ported from DuctusRenderingEngine.feedConsumer() but simplified:
        // - removed skip flag = !subpathStarted
        // - removed pathClosed (ie subpathStarted not set to false)
        boolean subpathStarted = false;

        for (; !pi.isDone(); pi.next()) {
            switch (pi.currentSegment(coords)) {
            case PathIterator.SEG_MOVETO:
                /* Checking SEG_MOVETO coordinates if they are out of the
                 * [LOWER_BND, UPPER_BND] range. This check also handles NaN
                 * and Infinity values. Skipping next path segment in case of
                 * invalid data.
                 */
                if (coords[0] < UPPER_BND && coords[0] > LOWER_BND &&
                    coords[1] < UPPER_BND && coords[1] > LOWER_BND)
                {
                    pc2d.moveTo(coords[0], coords[1]);
                    subpathStarted = true;
                }
                break;
            case PathIterator.SEG_LINETO:
                /* Checking SEG_LINETO coordinates if they are out of the
                 * [LOWER_BND, UPPER_BND] range. This check also handles NaN
                 * and Infinity values. Ignoring current path segment in case
                 * of invalid data. If segment is skipped its endpoint
                 * (if valid) is used to begin new subpath.
                 */
                if (coords[0] < UPPER_BND && coords[0] > LOWER_BND &&
                    coords[1] < UPPER_BND && coords[1] > LOWER_BND)
                {
                    if (subpathStarted) {
                        pc2d.lineTo(coords[0], coords[1]);
                    } else {
                        pc2d.moveTo(coords[0], coords[1]);
                        subpathStarted = true;
                    }
                }
                break;
            case PathIterator.SEG_QUADTO:
                // Quadratic curves take two points
                /* Checking SEG_QUADTO coordinates if they are out of the
                 * [LOWER_BND, UPPER_BND] range. This check also handles NaN
                 * and Infinity values. Ignoring current path segment in case
                 * of invalid endpoints's data. Equivalent to the SEG_LINETO
                 * if endpoint coordinates are valid but there are invalid data
                 * among other coordinates
                 */
                if (coords[2] < UPPER_BND && coords[2] > LOWER_BND &&
                    coords[3] < UPPER_BND && coords[3] > LOWER_BND)
                {
                    if (subpathStarted) {
                        if (coords[0] < UPPER_BND && coords[0] > LOWER_BND &&
                            coords[1] < UPPER_BND && coords[1] > LOWER_BND)
                        {
                            pc2d.quadTo(coords[0], coords[1],
                                        coords[2], coords[3]);
                        } else {
                            pc2d.lineTo(coords[2], coords[3]);
                        }
                    } else {
                        pc2d.moveTo(coords[2], coords[3]);
                        subpathStarted = true;
                    }
                }
                break;
            case PathIterator.SEG_CUBICTO:
                // Cubic curves take three points
                /* Checking SEG_CUBICTO coordinates if they are out of the
                 * [LOWER_BND, UPPER_BND] range. This check also handles NaN
                 * and Infinity values. Ignoring current path segment in case
                 * of invalid endpoints's data. Equivalent to the SEG_LINETO
                 * if endpoint coordinates are valid but there are invalid data
                 * among other coordinates
                 */
                if (coords[4] < UPPER_BND && coords[4] > LOWER_BND &&
                    coords[5] < UPPER_BND && coords[5] > LOWER_BND)
                {
                    if (subpathStarted) {
                        if (coords[0] < UPPER_BND && coords[0] > LOWER_BND &&
                            coords[1] < UPPER_BND && coords[1] > LOWER_BND &&
                            coords[2] < UPPER_BND && coords[2] > LOWER_BND &&
                            coords[3] < UPPER_BND && coords[3] > LOWER_BND)
                        {
                            pc2d.curveTo(coords[0], coords[1],
                                         coords[2], coords[3],
                                         coords[4], coords[5]);
                        } else {
                            pc2d.lineTo(coords[4], coords[5]);
                        }
                    } else {
                        pc2d.moveTo(coords[4], coords[5]);
                        subpathStarted = true;
                    }
                }
                break;
            case PathIterator.SEG_CLOSE:
                if (subpathStarted) {
                    pc2d.closePath();
                    // do not set subpathStarted to false
                    // in case of missing moveTo() after close()
                }
                break;
            default:
            }
        }
        pc2d.pathDone();

        // mark context as CLEAN:
        rdrCtx.dirty = false;
    }

    private static void feedConsumer(final DRendererContext rdrCtx,
                                     final Path2D p2d,
                                     final BaseTransform xform,
                                     final DPathConsumer2D pc2d)
    {
        // mark context as DIRTY:
        rdrCtx.dirty = true;

        final float[] coords = rdrCtx.float6;

        // ported from DuctusRenderingEngine.feedConsumer() but simplified:
        // - removed skip flag = !subpathStarted
        // - removed pathClosed (ie subpathStarted not set to false)
        boolean subpathStarted = false;

        final float[] pCoords = p2d.getFloatCoordsNoClone();
        final byte[] pTypes = p2d.getCommandsNoClone();
        final int nsegs = p2d.getNumCommands();

        for (int i = 0, coff = 0; i < nsegs; i++) {
            switch (pTypes[i]) {
            case PathIterator.SEG_MOVETO:
                if (xform == null) {
                    coords[0] = pCoords[coff];
                    coords[1] = pCoords[coff+1];
                } else {
                    xform.transform(pCoords, coff, coords, 0, 1);
                }
                coff += 2;
                /* Checking SEG_MOVETO coordinates if they are out of the
                 * [LOWER_BND, UPPER_BND] range. This check also handles NaN
                 * and Infinity values. Skipping next path segment in case of
                 * invalid data.
                 */
                if (coords[0] < UPPER_BND && coords[0] > LOWER_BND &&
                    coords[1] < UPPER_BND && coords[1] > LOWER_BND)
                {
                    pc2d.moveTo(coords[0], coords[1]);
                    subpathStarted = true;
                }
                break;
            case PathIterator.SEG_LINETO:
                if (xform == null) {
                    coords[0] = pCoords[coff];
                    coords[1] = pCoords[coff+1];
                } else {
                    xform.transform(pCoords, coff, coords, 0, 1);
                }
                coff += 2;
                /* Checking SEG_LINETO coordinates if they are out of the
                 * [LOWER_BND, UPPER_BND] range. This check also handles NaN
                 * and Infinity values. Ignoring current path segment in case
                 * of invalid data. If segment is skipped its endpoint
                 * (if valid) is used to begin new subpath.
                 */
                if (coords[0] < UPPER_BND && coords[0] > LOWER_BND &&
                    coords[1] < UPPER_BND && coords[1] > LOWER_BND)
                {
                    if (subpathStarted) {
                        pc2d.lineTo(coords[0], coords[1]);
                    } else {
                        pc2d.moveTo(coords[0], coords[1]);
                        subpathStarted = true;
                    }
                }
                break;
            case PathIterator.SEG_QUADTO:
                if (xform == null) {
                    coords[0] = pCoords[coff];
                    coords[1] = pCoords[coff+1];
                    coords[2] = pCoords[coff+2];
                    coords[3] = pCoords[coff+3];
                } else {
                    xform.transform(pCoords, coff, coords, 0, 2);
                }
                coff += 4;
                // Quadratic curves take two points
                /* Checking SEG_QUADTO coordinates if they are out of the
                 * [LOWER_BND, UPPER_BND] range. This check also handles NaN
                 * and Infinity values. Ignoring current path segment in case
                 * of invalid endpoints's data. Equivalent to the SEG_LINETO
                 * if endpoint coordinates are valid but there are invalid data
                 * among other coordinates
                 */
                if (coords[2] < UPPER_BND && coords[2] > LOWER_BND &&
                    coords[3] < UPPER_BND && coords[3] > LOWER_BND)
                {
                    if (subpathStarted) {
                        if (coords[0] < UPPER_BND && coords[0] > LOWER_BND &&
                            coords[1] < UPPER_BND && coords[1] > LOWER_BND)
                        {
                            pc2d.quadTo(coords[0], coords[1],
                                        coords[2], coords[3]);
                        } else {
                            pc2d.lineTo(coords[2], coords[3]);
                        }
                    } else {
                        pc2d.moveTo(coords[2], coords[3]);
                        subpathStarted = true;
                    }
                }
                break;
            case PathIterator.SEG_CUBICTO:
                if (xform == null) {
                    coords[0] = pCoords[coff];
                    coords[1] = pCoords[coff+1];
                    coords[2] = pCoords[coff+2];
                    coords[3] = pCoords[coff+3];
                    coords[4] = pCoords[coff+4];
                    coords[5] = pCoords[coff+5];
                } else {
                    xform.transform(pCoords, coff, coords, 0, 3);
                }
                coff += 6;
                // Cubic curves take three points
                /* Checking SEG_CUBICTO coordinates if they are out of the
                 * [LOWER_BND, UPPER_BND] range. This check also handles NaN
                 * and Infinity values. Ignoring current path segment in case
                 * of invalid endpoints's data. Equivalent to the SEG_LINETO
                 * if endpoint coordinates are valid but there are invalid data
                 * among other coordinates
                 */
                if (coords[4] < UPPER_BND && coords[4] > LOWER_BND &&
                    coords[5] < UPPER_BND && coords[5] > LOWER_BND)
                {
                    if (subpathStarted) {
                        if (coords[0] < UPPER_BND && coords[0] > LOWER_BND &&
                            coords[1] < UPPER_BND && coords[1] > LOWER_BND &&
                            coords[2] < UPPER_BND && coords[2] > LOWER_BND &&
                            coords[3] < UPPER_BND && coords[3] > LOWER_BND)
                        {
                            pc2d.curveTo(coords[0], coords[1],
                                         coords[2], coords[3],
                                         coords[4], coords[5]);
                        } else {
                            pc2d.lineTo(coords[4], coords[5]);
                        }
                    } else {
                        pc2d.moveTo(coords[4], coords[5]);
                        subpathStarted = true;
                    }
                }
                break;
            case PathIterator.SEG_CLOSE:
                if (subpathStarted) {
                    pc2d.closePath();
                    // do not set subpathStarted to false
                    // in case of missing moveTo() after close()
                }
                break;
            default:
            }
        }
        pc2d.pathDone();

        // mark context as CLEAN:
        rdrCtx.dirty = false;
    }
}
