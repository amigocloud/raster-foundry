"""Creates scenes for sentinel 2 imagery given a tile path"""

import json
import logging
import uuid

from rf.models import Scene
from rf.utils.io import JobStatus, Visibility

from .settings import bucket, s3, organization
from .create_footprint import create_footprint
from .create_thumbnails import create_thumbnails
from .create_images import create_images

logger = logging.getLogger(__name__)


def get_tileinfo(path):
    """Gets dictionary representation for scene tileinfo given a path

    Args:
        path (str): prefix for scene tile info (e.g. tiles/54/M/XB/2016/9/25/0)

    Returns:
        dict
    """
    logger.info('Getting tileinfo: %s', path)
    return json.loads(s3.Object(bucket.name, path).get()['Body'].read())


def create_sentinel2_scenes(tile_path):
    """Returns scenes that can be created via API given a path to tiles

    Args:
        tile_path (str): path to tile directory (e.g. tiles/54/M/XB/2016/9/25/0)

    Returns:
        List[Scene]
    """
    scene_id = str(uuid.uuid4())
    logger.info('Starting scene creation for sentinel 2 scene: %s', tile_path)
    metadata_file = '{path}/tileInfo.json'.format(path=tile_path)
    tileinfo = get_tileinfo(metadata_file)
    images = (create_images(scene_id, tileinfo, 10) +
              create_images(scene_id, tileinfo, 20) +
              create_images(scene_id, tileinfo, 60))
    footprint = create_footprint(tileinfo)
    thumbnails = create_thumbnails(scene_id, tile_path)
    tags = ['Sentinel-2', 'JPEG2000']
    datasource = 'Sentinel-2'

    scene_metadata = dict(
        path=tileinfo['path'],
        timestamp=tileinfo['timestamp'],
        utmZone=tileinfo['utmZone'],
        latitudeBand=tileinfo['latitudeBand'],
        gridSquare=tileinfo['gridSquare'],
        dataCoveragePercentage=tileinfo['dataCoveragePercentage'],
        cloudyPixelPercentage=tileinfo['cloudyPixelPercentage'],
        productName=tileinfo['productName'],
        productPath=tileinfo['productPath']
    )

    scene = Scene(
        organization,
        0,
        Visibility.PUBLIC,
        tags,
        datasource,
        scene_metadata,
        'S2 {}'.format(tile_path),  # name
        JobStatus.SUCCESS if thumbnails else JobStatus.FAILURE,
        JobStatus.SUCCESS if footprint else JobStatus.FAILURE,
        JobStatus.QUEUED,
        id=scene_id,
        acquisitionDate=tileinfo['timestamp'],
        cloudCover=tileinfo['cloudyPixelPercentage'],
        footprint=footprint,
        metadataFiles=[metadata_file],
        thumbnails=thumbnails,
        images=images
    )

    return [scene]
