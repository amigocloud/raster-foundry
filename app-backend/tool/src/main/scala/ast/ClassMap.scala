package com.azavea.rf.tool.ast

import io.circe.generic.JsonCodec
import geotrellis.raster._
import geotrellis.raster.render._
import spire.std.any._

@JsonCodec
case class ClassMap(
  classifications: Map[Double, Int],
  options: ClassMap.Options = ClassMap.Options()
) {
  lazy val mapStrategy =
    new MapStrategy(options.boundaryType, options.ndValue, options.fallback, false)

  def toBreakMap =
    new BreakMap(classifications, mapStrategy, { i: Double => isNoData(i) })

  def toColorMap =
    ColorMap(
      classifications,
      ColorMap.Options(
        options.boundaryType,
        options.ndValue,
        options.fallback
      )
    )
}

object ClassMap {
  @JsonCodec
  case class Options(
    boundaryType: ClassBoundaryType = LessThanOrEqualTo,
    ndValue: Int = NODATA,
    fallback: Int = NODATA
  )
}
