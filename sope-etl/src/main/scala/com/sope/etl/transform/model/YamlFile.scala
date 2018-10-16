package com.sope.etl.transform.model

import com.sope.etl.transform.Transformer
import com.sope.etl.transform.YamlParserUtil._
import com.sope.etl.transform.exception.YamlDataTransformException
import org.apache.spark.sql.{DataFrame, SQLContext}

/**
  * A wrapper class with utilities around Yaml file
  *
  * @author mbadgujar
  */
class YamlFile[T <: TransformModel](yamlPath: String, substitutions: Option[Seq[Any]] = None, modelClass : Class[T]) {

  private def updatePlaceHolders(): String = {
    substitutions.get
      .zipWithIndex
      .map { case (value, index) => "$" + (index + 1) -> convertToYaml(value) }
      .foldLeft(readYamlFile(yamlPath)) { case (yamlStr, (key, value)) => yamlStr.replace(key, value) }
  }

  /**
    * Get Yaml File Name
    *
    * @return [[String]] file name
    */
  def getYamlFileName: String = yamlPath.split("[\\\\/]").last

  /**
    * Get Yaml Text. Substitutes values if they are provided
    *
    * @return [[String]]
    */
  def getText: String = substitutions.fold(readYamlFile(yamlPath))(_ => updatePlaceHolders())

  /**
    * Serialize the YAML to provided Type
    *
    * @return T
    */
  def serialize: T = parseYAML(getText, modelClass)

}

object YamlFile {

  case class IntermediateYaml(yamlPath: String, substitutions: Option[Seq[Any]] = None)
    extends YamlFile(yamlPath, substitutions, classOf[TransformModelWithoutSourceTarget]){

    private val model = serialize

    /**
      * Perform transformation on provided dataframes.
      * The sources provided in YAML file should be equal and in-order to the provided dataframes
      *
      * @return Transformed [[DataFrame]]
      */
    def getTransformedDFs(dataFrames: DataFrame*): Seq[(String, DataFrame)] = {
      val transformModel = model.asInstanceOf[TransformModelWithoutSourceTarget]
      if (transformModel.sources.size != dataFrames.size)
        throw new YamlDataTransformException("Invalid Dataframes provided or incorrect yaml config")
      val sourceDFMap = transformModel.sources.zip(dataFrames).map { case (source, df) => (source, df.alias(source)) }
      new Transformer(getYamlFileName, sourceDFMap.toMap, transformModel.transformations).transform
    }
  }

  case class End2EndYaml(yamlPath: String) extends YamlFile(yamlPath, None, classOf[TransformModelWithSourceTarget]){

    private val model = serialize
    /**
      * Performs end to end transformations - Reading sources and writing transformation result to provided targets
      * The source yaml file should contains source and target information.
      *
      * @param sqlContext Spark [[SQLContext]]
      */
    def performTransformations(sqlContext: SQLContext): Unit = {
      val transformModel = model.asInstanceOf[TransformModelWithSourceTarget]
      val sourceDFMap = transformModel.asInstanceOf[TransformModelWithSourceTarget].sources.map(source => source.getSourceName
        -> source.apply(sqlContext).alias(source.getSourceName)).toMap
      val transformationResult = new Transformer(getYamlFileName, sourceDFMap, transformModel.transformations).transform.toMap
      transformModel.targets.foreach(target => target(transformationResult(target.getInput)))
    }
  }

}


